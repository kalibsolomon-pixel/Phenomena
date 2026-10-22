param([Parameter(ValueFromRemainingArguments=$true)][string[]]$GradleArgs=@('build'))
$repoRoot=Split-Path -Parent $PSScriptRoot
$localJdk=Get-ChildItem (Join-Path $repoRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if($localJdk){$env:JAVA_HOME=$localJdk.FullName}
Push-Location $repoRoot
try { & './gradlew.bat' @GradleArgs; $result=$LASTEXITCODE } finally { Pop-Location }
exit $result
