$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

function Write-Text([string]$path,[string]$text)
{
    $full=(Resolve-Path $path).Path
    [IO.File]::WriteAllText($full,$text,[Text.UTF8Encoding]::new($false))
}

function Relative-Luminance([string]$hex)
{
    $h=$hex.TrimStart('#')
    if($h.Length-ne 6){throw "Invalid RGB color: $hex"}
    $values=@(
        [Convert]::ToInt32($h.Substring(0,2),16)/255.0,
        [Convert]::ToInt32($h.Substring(2,2),16)/255.0,
        [Convert]::ToInt32($h.Substring(4,2),16)/255.0
    )
    $linear=@()
    foreach($v in $values)
    {
        if($v-le 0.04045){$linear+=($v/12.92)}
        else{$linear+=[Math]::Pow((($v+0.055)/1.055),2.4)}
    }
    return (0.2126*$linear[0])+(0.7152*$linear[1])+(0.0722*$linear[2])
}

function Contrast-Ratio([string]$a,[string]$b)
{
    $la=Relative-Luminance $a
    $lb=Relative-Luminance $b
    $hi=[Math]::Max($la,$lb)
    $lo=[Math]::Min($la,$lb)
    return ($hi+0.05)/($lo+0.05)
}

function Get-BrushColor([string]$xaml,[string]$key)
{
    $escaped=[regex]::Escape($key)
    $m=[regex]::Match($xaml,"<SolidColorBrush\s+x:Key=`"$escaped`"\s+Color=`"(?<color>#[0-9A-Fa-f]{6})`"\s*/>")
    if(-not $m.Success){throw "Brush not found: $key"}
    return $m.Groups['color'].Value.ToUpperInvariant()
}

$sln=Get-ChildItem (Join-Path $env:RUNNER_TEMP 'ari-src') -Recurse -Filter 'ARI.slnx' | Select-Object -First 1
if(-not $sln){throw 'Reconstructed ARI source not found.'}
$root=$sln.Directory.FullName
Set-Location $root

$app='.\src\Ari.Desktop\App.xaml'
$login='.\src\Ari.Desktop\Views\LoginWindow.xaml'
$main='.\src\Ari.Desktop\Views\MainWindow.xaml'

Write-Host '== Sidebar contrast hotfix =='
$xaml=Get-Content $app -Raw

# The implicit TextBlock foreground setter outranks inherited button foregrounds.
# Removing that setter lets sidebar button content inherit the high-contrast
# foreground supplied by NavButtonStyle/NavPrimaryButtonStyle while ordinary
# page text continues to inherit TextPrimaryBrush from Window.
$implicitTextBlock='(?ms)<Style\s+TargetType="TextBlock">\s*<Setter\s+Property="Foreground"\s+Value="\{StaticResource TextPrimaryBrush\}"\s*/>\s*</Style>'
if(-not [regex]::IsMatch($xaml,$implicitTextBlock)){throw 'Implicit TextBlock foreground style was not found.'}
$xaml=[regex]::Replace($xaml,$implicitTextBlock,'<Style TargetType="TextBlock"/>',1)

$oldOpacity='<Setter TargetName="NavChrome" Property="Opacity" Value="0.72"/>'
$newOpacity='<Setter TargetName="NavChrome" Property="Opacity" Value="0.85"/>'
if(-not $xaml.Contains($oldOpacity)){throw 'Disabled sidebar opacity marker was not found.'}
$xaml=$xaml.Replace($oldOpacity,$newOpacity)

$oldDisabled='<Setter Property="Foreground" Value="#91A6C2"/>'
$newDisabled='<Setter Property="Foreground" Value="{StaticResource SidebarMutedBrush}"/>'
if(-not $xaml.Contains($oldDisabled)){throw 'Disabled sidebar foreground marker was not found.'}
$xaml=$xaml.Replace($oldDisabled,$newDisabled)
Write-Text $app $xaml

Write-Host '== Structural and accessibility assertions =='
[xml](Get-Content $app -Raw) | Out-Null
[xml](Get-Content $login -Raw) | Out-Null
[xml](Get-Content $main -Raw) | Out-Null

$xaml=Get-Content $app -Raw
$mainText=Get-Content $main -Raw
$loginText=Get-Content $login -Raw

if([regex]::IsMatch($xaml,$implicitTextBlock)){throw 'Implicit TextBlock foreground override still exists.'}
if($xaml -notmatch 'x:Key="NavButtonStyle"'){throw 'NavButtonStyle is missing.'}
if($xaml -notmatch 'Property="Foreground" Value="\{StaticResource SidebarTextBrush\}"'){throw 'Normal sidebar text is not bound to SidebarTextBrush.'}
if($xaml -notmatch 'x:Key="NavPrimaryButtonStyle"'){throw 'NavPrimaryButtonStyle is missing.'}
if($xaml -notmatch 'Property="Foreground" Value="White"'){throw 'Active sidebar foreground is not white.'}
if($xaml -notmatch 'TargetName="NavChrome" Property="Opacity" Value="0.85"'){throw 'Disabled sidebar opacity was not raised.'}
if($xaml -notmatch 'Property="Foreground" Value="\{StaticResource SidebarMutedBrush\}"'){throw 'Disabled sidebar text does not use the readable muted brush.'}

$sidebar=Get-BrushColor $xaml 'SidebarBrush'
$normal=Get-BrushColor $xaml 'SidebarTextBrush'
$muted=Get-BrushColor $xaml 'SidebarMutedBrush'
$section=Get-BrushColor $xaml 'SidebarSectionHeaderBrush'
$active=Get-BrushColor $xaml 'SidebarActiveBrush'
$normalRatio=Contrast-Ratio $normal $sidebar
$mutedRatio=Contrast-Ratio $muted $sidebar
$sectionRatio=Contrast-Ratio $section $sidebar
$activeRatio=Contrast-Ratio '#FFFFFF' $active
Write-Host ("Sidebar contrast ratios: normal={0:N2}:1 muted={1:N2}:1 section={2:N2}:1 active={3:N2}:1" -f $normalRatio,$mutedRatio,$sectionRatio,$activeRatio)
if($normalRatio-lt 7.0){throw "Normal sidebar contrast too low: $normalRatio"}
if($mutedRatio-lt 7.0){throw "Muted sidebar contrast too low: $mutedRatio"}
if($sectionRatio-lt 7.0){throw "Sidebar section contrast too low: $sectionRatio"}
if($activeRatio-lt 4.5){throw "Active sidebar contrast too low: $activeRatio"}

$navCount=([regex]::Matches($mainText,'Style="\{StaticResource Nav(?:Primary)?ButtonStyle\}"')).Count
if($navCount-lt 20){throw "Expected at least 20 sidebar navigation buttons; found $navCount."}

foreach($pair in @(@('LoginWindow',$loginText),@('MainWindow',$mainText)))
{
    $name=$pair[0]
    $text=$pair[1]
    if($text -notmatch 'ResizeMode="CanResize"'){throw "$name is not resizable."}
    if($text -notmatch 'WindowStyle="SingleBorderWindow"'){throw "$name does not use standard Windows chrome."}
    if($text -match 'WindowStyle="None"'){throw "$name disables the standard close/minimize/maximize controls."}
}
if($loginText -notmatch 'by khaled altheeb'){throw 'Login signature is missing.'}
if($loginText -notmatch 'منصة محلية لتحليل السجلات والاتجاهات والتقارير البصرية مع حفظ المصدر والتاريخ'){throw 'Login platform description is missing.'}

Write-Host 'Sidebar readability, signature, resize, minimize/maximize and close-chrome assertions: PASS'

Get-ChildItem '.\src' -Directory -Recurse | Where-Object { $_.Name -in @('bin','obj') } | Sort-Object FullName -Descending | Remove-Item -Recurse -Force
python scripts/generate-manifest.py
if($LASTEXITCODE-ne 0){throw 'Manifest generation failed after sidebar hotfix.'}
python scripts/static-check.py
if($LASTEXITCODE-ne 0){throw 'Static verification failed after sidebar hotfix.'}
