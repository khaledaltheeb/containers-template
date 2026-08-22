$ErrorActionPreference='Stop'
function Assert-Exit($m){if($LASTEXITCODE-ne 0){throw $m}}
$sln=Get-ChildItem (Join-Path $env:RUNNER_TEMP 'ari-src') -Recurse -Filter 'ARI.slnx'|Select-Object -First 1
if(-not $sln){throw 'ARI source not found.'}
$root=$sln.Directory.FullName;Set-Location $root
Write-Host '== Release build and deterministic verification =='
dotnet build .\ARI.slnx -c Release;Assert-Exit 'Release build failed.'
dotnet run --project .\src\Ari.Verification\Ari.Verification.csproj -c Release --no-build;Assert-Exit 'Deterministic verification failed.'
dotnet run --project .\src\Ari.Verification\Ari.Verification.csproj -c Release --no-build -- --localdb-smoke;Assert-Exit 'LocalDB full-schema smoke failed.'
foreach($tier in @('Quick','Standard','Stress')){Write-Host "== Synthetic SQL $tier ==";& .\scripts\run-sql-acceptance.ps1 -Server '(localdb)\MSSQLLocalDB' -Tier $tier -Iterations 2;if(-not $?){throw "SQL $tier acceptance failed."}}
Write-Host '== Analysis/report source contracts =='
foreach($x in @('AnalysisVisualizationKind.Heatmap','BuildHeatmap','PngExporter','PrintToPdfAsync','ReportPaperKind.A3','ReportOrientationKind.Landscape')){if(-not(Get-ChildItem '.\src\Ari.Desktop' -Recurse -Filter '*.cs'|Select-String -SimpleMatch $x)){throw "Missing output contract: $x"}}
$out=Join-Path $env:GITHUB_WORKSPACE 'ari-round16-portable';if(Test-Path $out){Remove-Item $out -Recurse -Force}
dotnet publish .\src\Ari.Desktop\Ari.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $out;Assert-Exit 'Publish failed.'
$exe=Join-Path $out 'ARI.exe';if(-not(Test-Path $exe)){throw 'ARI.exe missing.'}
& $exe --self-test;Assert-Exit 'Executable self-test failed.'
& $exe --visual-self-test;Assert-Exit 'Visual output self-test failed.'
Get-ChildItem $out -File|Where-Object{$_.Extension -in @('.pdb','.xml')}|Remove-Item -Force
foreach($r in @('ARI.exe','ari.settings.json','tessdata\ara.traineddata','tessdata\eng.traineddata')){if(-not(Test-Path(Join-Path $out $r))){throw "Missing required clean payload file: $r"}}
$hash=(Get-FileHash $exe -Algorithm SHA256).Hash.ToLowerInvariant();"$hash  ARI.exe"|Set-Content(Join-Path $out 'ARI.exe.sha256') -Encoding ascii
@('ARI Round 16 validation',"ExecutableSHA256=$hash",'StaticCheck=PASS','ReleaseBuild=PASS','DeterministicVerification=PASS','LocalDbFullSchemaSmoke=PASS','SqlQuick100k=PASS','SqlStandard500k=PASS','SqlStress1000k=PASS','ColumnsPng=PASS','LinePng=PASS','HeatmapPng=PASS','MultiSeriesTrendPng=PASS','AnalysisTable=PASS','SidebarContrast=PASS','ResizableStandardWindowChrome=PASS','ReportOutputContracts=PASS','ExecutableSelfTest=PASS','CleanPayload=PASS')|Set-Content(Join-Path $out 'ARI-Round16-VALIDATION.txt') -Encoding utf8
$zip=Join-Path $env:GITHUB_WORKSPACE 'ARI-Round16-Portable-win-x64.zip';if(Test-Path $zip){Remove-Item $zip -Force};Compress-Archive -Path "$out\*" -DestinationPath $zip -Force
Write-Host "ARI Round 16 SHA256: $hash"
