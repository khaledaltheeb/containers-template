$ErrorActionPreference='Stop'
function Write-Text($path,$text){$full=(Resolve-Path $path).Path;[IO.File]::WriteAllText($full,$text,[Text.UTF8Encoding]::new($false))}
function Replace-Required($path,$old,$new){$t=Get-Content $path -Raw;if(-not $t.Contains($old)){throw "Pattern not found in $path"};Write-Text $path ($t.Replace($old,$new))}
$sln=Get-ChildItem (Join-Path $env:RUNNER_TEMP 'ari-src') -Recurse -Filter 'ARI.slnx'|Select-Object -First 1
if(-not $sln){throw 'Reconstructed ARI source not found.'}
$root=$sln.Directory.FullName;Set-Location $root
Copy-Item (Join-Path $env:GITHUB_WORKSPACE 'ari16-ui\App.xaml') '.\src\Ari.Desktop\App.xaml' -Force
Copy-Item (Join-Path $env:GITHUB_WORKSPACE 'ari16-ui\ReleaseVisualSelfTest.cs') '.\src\Ari.Desktop\Services\ReleaseVisualSelfTest.cs' -Force
Replace-Required '.\src\Ari.Desktop\Views\LoginWindow.xaml' 'Title="ARI - تسجيل الدخول" Width="840" Height="520" MinWidth="840" MinHeight="520"' 'Title="ARI - تسجيل الدخول" Width="840" Height="520" MinWidth="720" MinHeight="480"'
Replace-Required '.\src\Ari.Desktop\Views\LoginWindow.xaml' 'WindowStartupLocation="CenterScreen" ResizeMode="NoResize" FlowDirection="RightToLeft">' 'WindowStartupLocation="CenterScreen" ResizeMode="CanResize" WindowStyle="SingleBorderWindow" FlowDirection="RightToLeft">'
$login='.\src\Ari.Desktop\Views\LoginWindow.xaml';$t=Get-Content $login -Raw
$tag='<TextBlock Text="منصة محلية لتحليل السجلات والاتجاهات والتقارير البصرية مع حفظ المصدر والتاريخ." Foreground="#D2DBE8" FontSize="15" TextWrapping="Wrap" Margin="0,28,0,0" LineHeight="24"/>'
if(-not $t.Contains($tag)){throw 'Login tagline not found.'}
$sig=$tag+[Environment]::NewLine+'                    '+'<TextBlock Text="by khaled altheeb" Foreground="{StaticResource SignatureBrush}" FontSize="12" FontWeight="SemiBold" Margin="0,9,0,0" FlowDirection="LeftToRight"/>'
Write-Text $login ($t.Replace($tag,$sig))
Replace-Required '.\src\Ari.Desktop\Views\MainWindow.xaml' 'Title="ARI" Width="1440" Height="900" MinWidth="1180" MinHeight="740"' 'Title="ARI" Width="1360" Height="840" MinWidth="1024" MinHeight="640"'
Replace-Required '.\src\Ari.Desktop\Views\MainWindow.xaml' 'WindowStartupLocation="CenterScreen" FlowDirection="RightToLeft">' 'WindowStartupLocation="CenterScreen" ResizeMode="CanResize" WindowStyle="SingleBorderWindow" FlowDirection="RightToLeft">'
$main='.\src\Ari.Desktop\Views\MainWindow.xaml';$t=Get-Content $main -Raw
$t=$t.Replace('Foreground="{StaticResource SidebarMutedBrush}" FontSize="11" FontWeight="SemiBold"','Foreground="{StaticResource SidebarSectionHeaderBrush}" FontSize="11" FontWeight="SemiBold"')
$t=$t.Replace('<TextBlock x:Name="VersionText" Text="—" Foreground="#6F85A5" FontSize="10" Margin="0,6,0,0"/>','<TextBlock x:Name="VersionText" Text="—" Foreground="{StaticResource SidebarMutedBrush}" FontSize="10" Margin="0,6,0,0"/>')
Write-Text $main $t
$maintenance='.\src\Ari.Infrastructure\Data\SqlSystemMaintenanceRepository.cs'
Replace-Required $maintenance 'COALESCE((SELECT SUM(size)*8192 FROM sys.database_files),0),' 'COALESCE((SELECT SUM(CAST(size AS BIGINT))*CAST(8192 AS BIGINT) FROM sys.database_files),CAST(0 AS BIGINT)),'
$mt=Get-Content $maintenance -Raw
$pattern='(?ms)^(?<indent>[ \t]*)if\s*\(\s*string\.IsNullOrWhiteSpace\((?<diag>[A-Za-z_][A-Za-z0-9_]*)\.DatabaseBackupPath\)\s*\)\s*throw[^;\r\n]+;\s*\r?\n(?<indent2>[ \t]*)var\s+(?<pathvar>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*Path\.Combine\(\k<diag>\.DatabaseBackupPath,(?<tail>[^\r\n]+)\);'
$rx=[regex]::new($pattern)
$m=$rx.Match($mt)
if(-not $m.Success){
    Write-Host 'Backup fallback target lines:'
    Get-Content $maintenance | Select-String -Pattern 'DatabaseBackupPath|Path.Combine|InvalidOperationException' | ForEach-Object { Write-Host ("{0}: {1}" -f $_.LineNumber,$_.Line) }
    throw 'Could not locate LocalDB backup-path guard block.'
}
$nl=[Environment]::NewLine
$replacement=$m.Groups['indent'].Value+'var backupDirectory = '+$m.Groups['diag'].Value+'.DatabaseBackupPath;'+$nl+
    $m.Groups['indent'].Value+'if (string.IsNullOrWhiteSpace(backupDirectory))'+$nl+
    $m.Groups['indent'].Value+'{'+$nl+
    $m.Groups['indent'].Value+'    backupDirectory = Path.Combine(Path.GetTempPath(), "ARI-Backups");'+$nl+
    $m.Groups['indent'].Value+'    Directory.CreateDirectory(backupDirectory);'+$nl+
    $m.Groups['indent'].Value+'}'+$nl+
    $m.Groups['indent2'].Value+'var '+$m.Groups['pathvar'].Value+' = Path.Combine(backupDirectory,'+$m.Groups['tail'].Value+');'
$mt=$rx.Replace($mt,$replacement,1)
Write-Text $maintenance $mt
$app='.\src\Ari.Desktop\App.xaml.cs';$t=Get-Content $app -Raw
$marker='        if (e.Args.Any(x => string.Equals(x, "--self-test", StringComparison.OrdinalIgnoreCase)))'
$idx=$t.IndexOf($marker,[StringComparison]::Ordinal)
if($idx-lt 0){throw 'App self-test marker not found.'}
$nl=[Environment]::NewLine
$visual='        if (e.Args.Any(x => string.Equals(x, "--visual-self-test", StringComparison.OrdinalIgnoreCase)))'+$nl+'        {'+$nl+'            try { ReleaseVisualSelfTest.Run(); Shutdown(0); }'+$nl+'            catch (Exception ex) { ApplicationErrorService.Log(ex, "Visual release self-test"); Shutdown(24); }'+$nl+'            return;'+$nl+'        }'+$nl+$nl
Write-Text $app ($t.Insert($idx,$visual))
[xml](Get-Content '.\src\Ari.Desktop\App.xaml' -Raw)|Out-Null;[xml](Get-Content $login -Raw)|Out-Null;[xml](Get-Content $main -Raw)|Out-Null
Get-ChildItem '.\src' -Directory -Recurse | Where-Object { $_.Name -in @('bin','obj') } | Sort-Object FullName -Descending | Remove-Item -Recurse -Force
python scripts/generate-manifest.py
if($LASTEXITCODE-ne 0){throw 'Manifest generation failed.'}
python scripts/static-check.py
if($LASTEXITCODE-ne 0){throw 'Static verification failed.'}
