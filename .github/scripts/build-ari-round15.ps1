$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ExitCode([string]$message) {
    if ($LASTEXITCODE -ne 0) { throw $message }
}

function Expand-XzPatch([string]$base64Path, [int]$expectedLength, [string]$expectedXzHash, [string]$expectedPatchHash, [string]$outputName) {
    $b64 = (Get-Content $base64Path -Raw) -replace '\s',''
    if ($b64.Length -ne $expectedLength) { throw "Unexpected Base64 length for $outputName: $($b64.Length)" }
    $xz = Join-Path $env:RUNNER_TEMP "$outputName.xz"
    [IO.File]::WriteAllBytes($xz, [Convert]::FromBase64String($b64))
    $xzHash = (Get-FileHash $xz -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($xzHash -ne $expectedXzHash) { throw "$outputName compressed SHA256 mismatch: $xzHash" }
    $patch = Join-Path $env:RUNNER_TEMP $outputName
    python -c "import lzma,sys; open(sys.argv[2],'wb').write(lzma.open(sys.argv[1],'rb').read())" $xz $patch
    Assert-ExitCode "$outputName decompression failed."
    $patchHash = (Get-FileHash $patch -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($patchHash -ne $expectedPatchHash) { throw "$outputName SHA256 mismatch: $patchHash" }
    return $patch
}

Write-Host '== Reconstruct verified Round 14 source =='
$paths = @(
    'ari14-final/part-00.txt','ari14-final/part-01.txt','ari14-final/part-02.txt','ari14-final/part-03.txt',
    'ari14-fix10/p04-0.txt','ari14-fix2k/p04-1-0-0.txt','ari14-fix2k/p04-1-0-1.txt','ari14-fix2k/p04-1-0-2.txt','ari14-fix5/p04-1-1.txt',
    'ari14-final/part-05.txt','ari14-final/part-06.txt',
    'ari14-fix10/p07-0.txt','ari14-fix10/p07-1.txt','ari14-fix10/p08-0.txt','ari14-fix10/p08-1.txt','ari14-fix10/p09-0.txt','ari14-fix10/p09-1.txt',
    'ari14-fix10/p10-0.txt','ari14-fix10/p10-1.txt','ari14-fix10/p11-0.txt','ari14-fix10/p11-1.txt','ari14-fix10/p12-0.txt',
    'ari14-fix2k/p12-1-0-0.txt','ari14-fix2k/p12-1-0-1.txt','ari14-fix2k/p12-1-0-2.txt','ari14-fix2k/p12-1-1-0.txt','ari14-fix2k/p12-1-1-1.txt','ari14-fix2k/p12-1-1-2.txt',
    'ari14-fix10/p13-0.txt'
)
foreach ($p in $paths) {
    if (-not (Test-Path (Join-Path $env:GITHUB_WORKSPACE $p))) { throw "Missing ARI source part: $p" }
}
$b64 = ($paths | ForEach-Object { Get-Content (Join-Path $env:GITHUB_WORKSPACE $_) -Raw }) -join ''
$b64 = $b64 -replace '\s',''
if ($b64.Length -ne 262360) { throw "Unexpected ARI Base64 length: $($b64.Length)" }
$archive = Join-Path $env:RUNNER_TEMP 'ari-round14.tar.xz'
[IO.File]::WriteAllBytes($archive, [Convert]::FromBase64String($b64))
$sourceHash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne 'bc79f84cce619fbf0b278b99670a534743bf9b4ac7f4fe521c177a0f2c9f49a9') { throw "ARI source SHA256 mismatch: $sourceHash" }
$extract = Join-Path $env:RUNNER_TEMP 'ari-src'
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
New-Item -ItemType Directory -Force -Path $extract | Out-Null
tar -xJf $archive -C $extract
Assert-ExitCode 'ARI source extraction failed.'
$sln = Get-ChildItem $extract -Recurse -Filter 'ARI.slnx' | Select-Object -First 1
if (-not $sln) { throw 'ARI.slnx not found after extraction.' }
$ariRoot = $sln.Directory.FullName
Set-Location $ariRoot
Write-Host "ARI source root: $ariRoot"

Write-Host '== Apply verified Round 14 comprehensive fixes =='
$fixParts = 0..5 | ForEach-Object { "ari14-srcfix-v2/part-{0:d2}.txt" -f $_ }
$fixB64 = ($fixParts | ForEach-Object { Get-Content (Join-Path $env:GITHUB_WORKSPACE $_) -Raw }) -join ''
$fixB64 = $fixB64 -replace '\s',''
if ($fixB64.Length -ne 10512) { throw "Unexpected Round 14 fix Base64 length: $($fixB64.Length)" }
$fixXz = Join-Path $env:RUNNER_TEMP 'ari-round14-fixes.patch.xz'
[IO.File]::WriteAllBytes($fixXz, [Convert]::FromBase64String($fixB64))
if ((Get-FileHash $fixXz -Algorithm SHA256).Hash.ToLowerInvariant() -ne '0f5ada5b19f11ec6ae22fdf0aeddbc56577ac7ccc5747a488c788e334bbeed4d') { throw 'Round 14 compressed patch hash mismatch.' }
$fixPatch = Join-Path $env:RUNNER_TEMP 'ari-round14-fixes.patch'
python -c "import lzma,sys; open(sys.argv[2],'wb').write(lzma.open(sys.argv[1],'rb').read())" $fixXz $fixPatch
Assert-ExitCode 'Round 14 patch decompression failed.'
if ((Get-FileHash $fixPatch -Algorithm SHA256).Hash.ToLowerInvariant() -ne '4b8f1e36b7160b57a0bae0501c2af303a745454b91e4fa7f7373645792213d43') { throw 'Round 14 patch hash mismatch.' }
git apply --check $fixPatch
Assert-ExitCode 'Round 14 patch preflight failed.'
git apply $fixPatch
Assert-ExitCode 'Round 14 patch application failed.'

Write-Host '== Apply verified Round 15 portability patch =='
$round15Patch = Expand-XzPatch `
    (Join-Path $env:GITHUB_WORKSPACE 'ari15-patch\round15.patch.xz.b64') `
    16424 `
    'ada9ee78312841fb19d8267958449996a3f9ac7edb0ce938f7d9d157f6a97b77' `
    '85e897206a148a22db4d88bbdc109eb241857e5b80beae2c88679be19048cf4b' `
    'ari-round15.patch'
git apply --check --exclude=manifest.json $round15Patch
Assert-ExitCode 'Round 15 patch preflight failed.'
git apply --exclude=manifest.json $round15Patch
Assert-ExitCode 'Round 15 patch application failed.'

Write-Host '== Apply LocalDB Full-Text compatibility hotfix =='
$fullTextPatch = Expand-XzPatch `
    (Join-Path $env:GITHUB_WORKSPACE 'ari15-hotfix\fulltext-localdb.patch.xz.b64') `
    1068 `
    '9f74e5212ad695f321ac72115154a86fbe90e36d854a4bdab5900bd8d389979d' `
    'c3acf1d473c2e536c840a76c6772085d1d412a801cdfb373623f910fd120e24d' `
    'ari-localdb-fulltext.patch'
git apply --check $fullTextPatch
Assert-ExitCode 'LocalDB Full-Text hotfix preflight failed.'
git apply $fullTextPatch
Assert-ExitCode 'LocalDB Full-Text hotfix application failed.'

Write-Host '== Download and authenticate Microsoft LocalDB runtime =='
$runtimeDir = Join-Path $ariRoot 'src\Ari.Desktop\Runtime'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$msi = Join-Path $runtimeDir 'SqlLocalDB.msi'
Invoke-WebRequest -Uri 'https://download.microsoft.com/download/3/8/d/38de7036-2433-4207-8eae-06e247e17b25/SqlLocalDB.msi' -OutFile $msi
$msiHash = (Get-FileHash $msi -Algorithm SHA256).Hash.ToLowerInvariant()
if ($msiHash -ne '224d483992ef60368dac70cea174dcfaf43a3ca06ada331c67dc6119a26490f6') { throw "LocalDB MSI SHA256 mismatch: $msiHash" }
$signature = Get-AuthenticodeSignature $msi
if ($signature.Status -ne 'Valid') { throw "LocalDB MSI Authenticode status is $($signature.Status)." }
if ($signature.SignerCertificate.Subject -notmatch 'Microsoft') { throw "Unexpected LocalDB MSI signer: $($signature.SignerCertificate.Subject)" }
Write-Host "LocalDB MSI verified: $msiHash"

Write-Host '== Validate Round 15 source =='
$props = Get-Content 'Directory.Build.props' -Raw
if ($props -notmatch '<Version>0\.15\.0</Version>') { throw 'Product Version is not 0.15.0.' }
if ($props -notmatch '<FileVersion>0\.15\.0\.0</FileVersion>') { throw 'FileVersion is not 0.15.0.0.' }
if ($props -notmatch '<AssemblyVersion>0\.15\.0\.0</AssemblyVersion>') { throw 'AssemblyVersion is not 0.15.0.0.' }
python scripts/generate-manifest.py
Assert-ExitCode 'Manifest generation failed.'
python scripts/static-check.py
Assert-ExitCode 'Static verification failed.'

Write-Host '== Prepare OCR data =='
if (Test-Path 'scripts/prepare-ocr-data.ps1') { & .\scripts\prepare-ocr-data.ps1 }
$tess = Join-Path $ariRoot 'src\Ari.Desktop\tessdata'
foreach ($lang in @('eng.traineddata','ara.traineddata')) {
    $file = Join-Path $tess $lang
    if (-not (Test-Path $file)) { throw "OCR language file missing: $lang" }
    if ((Get-Item $file).Length -lt 100000) { throw "OCR language file is incomplete: $lang" }
}

Write-Host '== Restore and build Release =='
dotnet restore .\ARI.slnx
Assert-ExitCode 'dotnet restore failed.'
dotnet build .\ARI.slnx -c Release --no-restore
Assert-ExitCode 'dotnet build failed.'

Write-Host '== Deterministic verification =='
dotnet run --project .\src\Ari.Verification\Ari.Verification.csproj -c Release --no-build
Assert-ExitCode 'Ari.Verification failed.'

Write-Host '== Ensure LocalDB runtime =='
$proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', "`"$msi`"", 'IACCEPTSQLLOCALDBLICENSETERMS=YES', '/qn', '/norestart') -Wait -PassThru
if ($proc.ExitCode -notin @(0, 1638, 1641, 3010)) { throw "LocalDB MSI install failed with exit code $($proc.ExitCode)." }
$localDbExe = Get-ChildItem "$env:ProgramFiles\Microsoft SQL Server" -Filter 'SqlLocalDB.exe' -File -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $localDbExe) { throw 'SqlLocalDB.exe was not found after LocalDB installation.' }
& $localDbExe.FullName info MSSQLLocalDB *> $null
if ($LASTEXITCODE -ne 0) {
    & $localDbExe.FullName create MSSQLLocalDB
    Assert-ExitCode 'Unable to create MSSQLLocalDB instance.'
}
& $localDbExe.FullName start MSSQLLocalDB
Assert-ExitCode 'Unable to start MSSQLLocalDB instance.'

Write-Host '== LocalDB full schema smoke =='
dotnet run --project .\src\Ari.Verification\Ari.Verification.csproj -c Release --no-build -- --localdb-smoke
Assert-ExitCode 'ARI LocalDB smoke test failed.'

Write-Host '== Publish and self-test portable win-x64 =='
$out = Join-Path $env:GITHUB_WORKSPACE 'ari-portable'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
dotnet publish .\src\Ari.Desktop\Ari.Desktop.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $out
Assert-ExitCode 'dotnet publish failed.'
$exe = Join-Path $out 'ARI.exe'
if (-not (Test-Path $exe)) { throw 'ARI.exe was not produced.' }
$info = [Diagnostics.FileVersionInfo]::GetVersionInfo($exe)
if ($info.FileVersion -ne '0.15.0.0') { throw "Unexpected ARI.exe FileVersion: $($info.FileVersion)" }
& $exe --self-test
Assert-ExitCode 'ARI.exe self-test failed.'
$exeHash = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLowerInvariant()
"$exeHash  ARI.exe" | Set-Content (Join-Path $out 'ARI.exe.sha256') -Encoding ascii
$zip = Join-Path $env:GITHUB_WORKSPACE 'ARI-Round15-Portable-win-x64.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path "$out\*" -DestinationPath $zip -Force
@(
    'ARI Round 15 Windows release validation',
    "FileVersion=0.15.0.0",
    "ExecutableSHA256=$exeHash",
    "LocalDbMsiSHA256=$msiHash",
    'StaticCheck=PASS',
    'ReleaseBuild=PASS',
    'DeterministicVerification=PASS',
    'LocalDbFullSchemaSmoke=PASS',
    'ExecutableSelfTest=PASS'
) | Set-Content (Join-Path $out 'ARI-Round15-VALIDATION.txt') -Encoding utf8
Write-Host "ARI Round 15 executable SHA256: $exeHash"
