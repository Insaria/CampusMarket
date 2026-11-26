[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'Migrate')]
    [string]$Mode = 'Preflight',
    [ValidateRange(1, 65535)]
    [int]$Port = 3306,
    [string]$ProductImagesDir,
    [string]$SellerDocumentsDir
)

$ErrorActionPreference = 'Stop'
if (-not $ProductImagesDir) {
    $ProductImagesDir = if ($env:PRODUCT_IMAGES_DIR) { $env:PRODUCT_IMAGES_DIR }
    else { Join-Path (Split-Path -LiteralPath $PSScriptRoot) 'CampusMarket-data\product-images' }
}
if (-not $SellerDocumentsDir) {
    $SellerDocumentsDir = if ($env:SELLER_PRIVATE_DOCUMENTS_DIR) { $env:SELLER_PRIVATE_DOCUMENTS_DIR }
    else { Join-Path $env:LOCALAPPDATA 'CampusMarket\private-seller-documents' }
}

function Get-StorageFileNames([string]$Dir) {
    if (Test-Path -LiteralPath $Dir -PathType Container) {
        Get-ChildItem -LiteralPath $Dir -File | Where-Object {
            $_.Length -gt 0 -and -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
        } | Select-Object -ExpandProperty Name
    }
}

function Find-ClassicClient([string]$Name) {
    $client = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue
    if ($client) { return $client.Source }
    $workbenchClient = Join-Path $env:ProgramFiles "MySQL\MySQL Workbench 8.0\$Name"
    if (Test-Path -LiteralPath $workbenchClient -PathType Leaf) { return $workbenchClient }
    throw "Cannot find $Name on PATH or in the MySQL Workbench directory."
}

function Assert-RestorableDump([string]$Path) {
    $tables = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $completed = $false
    $lineNumber = 0
    $reader = [IO.File]::OpenText($Path)
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lineNumber++
            if ($line -match '^\s*(?:/\*![0-9]+\s*)?(?:USE\b|DROP\b|TRUNCATE\b|LOAD\s+DATA\b|(?:CREATE|ALTER)\s+(?:DATABASE|SCHEMA|VIEW|TRIGGER|PROCEDURE|FUNCTION|EVENT)\b|\\)') {
                throw "Backup contains an unexpected statement at line ${lineNumber}. Target restore stopped."
            }
            if ($line -match '^CREATE TABLE\s+\x60([A-Za-z0-9_]+)\x60\s*\(') {
                if (-not $tables.Add($Matches[1])) { throw "Backup contains a duplicate table at line ${lineNumber}." }
            } elseif ($line -match '^\s*CREATE\s+TABLE\b') {
                throw "Backup contains an unexpected table definition at line ${lineNumber}."
            }
            if ($line -match '^\s*INSERT\s+INTO\b') {
                # mysqldump lists writable columns explicitly when a table has generated columns.
                if ($line -notmatch '^INSERT INTO\s+\x60([A-Za-z0-9_]+)\x60\s*(?:\(\s*\x60[A-Za-z0-9_]+\x60(?:\s*,\s*\x60[A-Za-z0-9_]+\x60)*\s*\)\s*)?VALUES\s*\(' -or
                    -not $tables.Contains($Matches[1])) {
                    throw "Backup contains an unexpected INSERT target at line ${lineNumber}."
                }
            }
            if ($line -match '^-- Dump completed on ') { $completed = $true }
        }
    } finally {
        $reader.Dispose()
    }
    if ($tables.Count -ne 26 -or -not $completed) {
        throw 'Backup is incomplete or table count is unexpected. Target restore stopped.'
    }
}

$node = Get-Command node.exe -CommandType Application -ErrorAction Stop
$mysql = Find-ClassicClient 'mysql.exe'
$scriptFile = Join-Path $PSScriptRoot 'database\sql\stage61-student-schema-copy.js'
$unique = [Guid]::NewGuid().ToString('N')
$backupRoot = 'D:\CampusMarket-backups'
$backupContainer = Join-Path $backupRoot ('campus_market_dev-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $unique)
$backup = Join-Path $backupContainer 'campus_market_dev.sql'

if ($Mode -eq 'Migrate') {
    if (netstat.exe -ano -p TCP | Select-String ':8080\s+\S+\s+LISTENING\s+\d+$') {
        throw 'Port 8080 is still listening. Stop the current CampusMarket backend yourself before migration.'
    }
    $approval = Read-Host 'When all app instances, jobs and other source/target writers are stopped, type 2024011324 to proceed'
    if ($approval -cne '2024011324') { throw 'Maintenance window not confirmed.' }
    if (Test-Path -LiteralPath $backupContainer) { throw 'Backup path already exists.' }
    New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $backupContainer | Out-Null
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $backupContainer
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl',
        'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $backupContainer -AclObject $acl
}

$user = Read-Host 'Local MySQL administrator username'
if ($user -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Enter a local MySQL username, without a host or password.' }
$manifest = Join-Path ([IO.Path]::GetTempPath()) ('campus-copy-images-' + $unique + '.json')
$copyVars = @{
    CAMPUS_COPY_MODE = 'Preflight'
    CAMPUS_COPY_IMAGE_MANIFEST = $manifest
    CAMPUS_COPY_MYSQL_USER = $user
    CAMPUS_COPY_MYSQL_PORT = [string]$Port
    CAMPUS_COPY_MYSQL_CLIENT = $mysql
}
$previous = @{}
$migrationLock = $null
function Invoke-CopyPhase([string]$Phase, [string[]]$PhaseArgs = @()) {
    $env:CAMPUS_COPY_MODE = $Phase
    & $node.Source $scriptFile @PhaseArgs
    if ($LASTEXITCODE -ne 0) {
        if ($Mode -eq 'Preflight') { exit $LASTEXITCODE }
        if ($Phase -eq 'Restore') {
            throw "Target restore failed (exit code $LASTEXITCODE). Keep the backup and do not retry over a partial target."
        }
        throw "MySQL client phase $Phase failed (exit code $LASTEXITCODE)."
    }
}

try {
    $files = @{
        products = @(Get-StorageFileNames $ProductImagesDir)
        documents = @(Get-StorageFileNames $SellerDocumentsDir)
    }
    [IO.File]::WriteAllText($manifest, ($files | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    foreach ($entry in $copyVars.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    if ($Mode -eq 'Preflight') {
        Invoke-CopyPhase 'Preflight'
        exit 0
    }
    $dump = Find-ClassicClient 'mysqldump.exe'
    $migrationLock = [IO.File]::Open((Join-Path $backupRoot 'copy-2024011324.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    Invoke-CopyPhase 'Preflight'
    if (Test-Path -LiteralPath $backup) { throw 'Backup file already exists.' }
    $connection = @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$Port", "--user=$user", '-p')
    $dumpOptions = @('--single-transaction', '--quick', '--skip-lock-tables', '--skip-add-locks',
        '--skip-add-drop-table', '--no-create-db', '--no-tablespaces', '--hex-blob', '--set-gtid-purged=OFF',
        '--default-character-set=utf8mb4', "--result-file=$backup", 'campus_market_dev')
    Write-Host 'Backing up the source database. Enter the administrator password at the native client prompt.'
    & $dump @connection @dumpOptions
    if ($LASTEXITCODE -ne 0) { throw "Source backup failed (exit code $LASTEXITCODE). Target restore stopped." }
    Assert-RestorableDump $backup
    Write-Host "Source backup completed: $backup"
    Invoke-CopyPhase 'PrepareTarget'
    Write-Host 'Restoring SQL into 2024011324. Enter the administrator password at the native client prompt.'
    Invoke-CopyPhase 'Restore' @($backup)
    Invoke-CopyPhase 'Verify'
} finally {
    if ($null -ne $migrationLock) { $migrationLock.Dispose() }
    foreach ($entry in $previous.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    if (Test-Path -LiteralPath $manifest) { Remove-Item -LiteralPath $manifest }
}
