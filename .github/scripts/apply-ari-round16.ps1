$ErrorActionPreference='Stop'
function Replace-Required($path,$old,$new){$t=Get-Content $path -Raw;if(-not $t.Contains($old)){throw "Pattern not found in $path"};[IO.File]::WriteAllText($path,$t.Replace($old,$new),[Text.UTF8Encoding]::new($false))}
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
$sig=$tag+"`r`n                    "+'<TextBlock Text="by khaled altheeb" Foreground="{StaticResource SignatureBrush}" FontSize="12" FontWeight="SemiBold" Margin="0,9,0,0" FlowDirection="LeftToRight"/>'
[IO.File]::WriteAllText($login,$t.Replace($tag,$sig),[Text.UTF8Encoding]::new($false))
Replace-Required '.\src\Ari.Desktop\Views\MainWindow.xaml' 'Title="ARI" Width="1440" Height="900" MinWidth="1180" MinHeight="740"' 'Title="ARI" Width="1360" Height="840" MinWidth="1024" MinHeight="640"'
Replace-Required '.\src\Ari.Desktop\Views\MainWindow.xaml' 'WindowStartupLocation="CenterScreen" FlowDirection="RightToLeft">' 'WindowStartupLocation="CenterScreen" ResizeMode="CanResize" WindowStyle="SingleBorderWindow" FlowDirection="RightToLeft">'
$main='.\src\Ari.Desktop\Views\MainWindow.xaml';$t=Get-Content $main -Raw
$t=$t.Replace('Foreground="{StaticResource SidebarMutedBrush}" FontSize="11" FontWeight="SemiBold"','Foreground="{StaticResource SidebarSectionHeaderBrush}" FontSize="11" FontWeight="SemiBold"')
$t=$t.Replace('<TextBlock x:Name="VersionText" Text="—" Foreground="#6F85A5" FontSize="10" Margin="0,6,0,0"/>','<TextBlock x:Name="VersionText" Text="—" Foreground="{StaticResource SidebarMutedBrush}" FontSize="10" Margin="0,6,0,0"/>')
[IO.File]::WriteAllText($main,$t,[Text.UTF8Encoding]::new($false))
$app='.\src\Ari.Desktop\App.xaml.cs';$t=Get-Content $app -Raw
$marker="        if (e.Args.Any(x => string.Equals(x, `"--self-test`", StringComparison.OrdinalIgnoreCase)))`r`n        {`r`n            RunReleaseSelfTest();`r`n            return;`r`n        }"
if(-not $t.Contains($marker)){throw 'App self-test marker not found.'}
$visual="        if (e.Args.Any(x => string.Equals(x, `"--visual-self-test`", StringComparison.OrdinalIgnoreCase)))`r`n        {`r`n            try { ReleaseVisualSelfTest.Run(); Shutdown(0); }`r`n            catch (Exception ex) { ApplicationErrorService.Log(ex, `"Visual release self-test`"); Shutdown(24); }`r`n            return;`r`n        }`r`n`r`n"
[IO.File]::WriteAllText($app,$t.Replace($marker,$visual+$marker),[Text.UTF8Encoding]::new($false))
[xml](Get-Content '.\src\Ari.Desktop\App.xaml' -Raw)|Out-Null;[xml](Get-Content $login -Raw)|Out-Null;[xml](Get-Content $main -Raw)|Out-Null
python scripts/generate-manifest.py
if($LASTEXITCODE-ne 0){throw 'Manifest generation failed.'}
python scripts/static-check.py
if($LASTEXITCODE-ne 0){throw 'Static verification failed.'}
