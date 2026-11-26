[CmdletBinding()]
param([switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$schema = '2024011324'
$clients = Join-Path $env:ProgramFiles 'MySQL\MySQL Workbench 8.0'
$mysql = Join-Path $clients 'mysql.exe'
$dump = Join-Path $clients 'mysqldump.exe'
$migration = Join-Path $PSScriptRoot 'database\sql\6.2_product_messages.sql'
$expected = 'CB7CFCA8BBAB9A13B19CC7AC9C0AC9F67A30C37C74400F92A425F75D779D3B1E'
$rawSchema = '`2024011324`'

foreach ($path in $mysql, $dump, $migration) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing required file: $path" }
}
if ((Get-FileHash -LiteralPath $migration -Algorithm SHA256).Hash -ne $expected) {
    throw 'The migration file does not match the reviewed copy.'
}

function Read-MySqlUser([string]$Prompt) {
    $name = Read-Host $Prompt
    if ($name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Enter a local MySQL user name only.' }
    return $name
}

function Invoke-Sql([string]$User, [string]$Sql) {
    $output = $Sql | & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 "--user=$User" --password --default-character-set=utf8mb4 --batch --skip-column-names --database=$schema
    if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed. Stop before switching the backend.' }
    return $output
}

$admin = Read-MySqlUser 'MySQL administrator user'
$app = Read-MySqlUser 'Existing CampusMarket database user'
if (!$VerifyOnly) {
    Write-Host 'Targeted preflight. Enter the administrator password at the MySQL prompt.'
    $version = @(Invoke-Sql $admin @"
SELECT DATABASE(), VERSION(),
       (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'product_messages'),
       (SELECT COUNT(*) FROM information_schema.tables t
        JOIN information_schema.columns c ON c.table_schema = t.table_schema AND c.table_name = t.table_name
        WHERE t.table_schema = DATABASE() AND t.table_name IN ('users', 'products')
          AND t.table_type = 'BASE TABLE' AND t.engine = 'InnoDB'
          AND c.column_name = 'id' AND c.data_type = 'bigint' AND c.column_type NOT LIKE '%unsigned%'
          AND c.column_key = 'PRI'),
       (SELECT COUNT(*) FROM information_schema.collations WHERE collation_name = 'utf8mb4_0900_ai_ci');
"@)
    if ($version.Count -ne 1) { throw 'Unexpected targeted preflight result.' }
    $fields = $version[0] -split "`t"
    if ($fields.Count -ne 5 -or $fields[0] -cne $schema) { throw 'Wrong MySQL schema.' }
    if ($fields[2] -ne '0') { throw 'The product_messages name already exists. No migration attempted.' }
    $serverVersion = [regex]::Match($fields[1], '^\d+\.\d+\.\d+')
    if (!$serverVersion.Success -or $fields[1] -match 'MariaDB' -or
        [version]$serverVersion.Value -lt [version]'8.0.16' -or $fields[3] -ne '2' -or $fields[4] -ne '1') {
        throw 'Server version, parent table keys, or collation do not match the reviewed migration.'
    }

    $backupRoot = 'D:\CampusMarket-backups'
    $null = New-Item -ItemType Directory -Path $backupRoot -Force
    $backup = Join-Path $backupRoot ('stage82-before-product-messages-' + [guid]::NewGuid().ToString('N') + '.sql')
    Write-Host 'Backing up the three unchanged table definitions. Enter the administrator password.'
    & $dump --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 "--user=$admin" --password --no-data --skip-add-drop-table --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF "--result-file=$backup" $schema users products notifications
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $backup -PathType Leaf)) { throw 'Backup failed. No migration attempted.' }
    foreach ($table in 'users','products','notifications') {
        if (!(Select-String -LiteralPath $backup -SimpleMatch ('CREATE TABLE `' + $table + '`') -Quiet)) {
            throw 'Backup is incomplete. No migration attempted.'
        }
    }
    Write-Host "Backup: $backup"
    Write-Host "Backup SHA-256: $((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash)"
}

$verification = @"
SELECT DATABASE(), (SELECT COUNT(*) FROM $rawSchema.product_messages),
       (SELECT COUNT(*) FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'product_messages'
          AND ((constraint_type = 'FOREIGN KEY' AND constraint_name IN (
            'fk_product_messages_product','fk_product_messages_buyer','fk_product_messages_seller','fk_product_messages_sender'))
            OR (constraint_type = 'CHECK' AND enforced = 'YES' AND constraint_name IN (
            'ck_product_messages_participants','ck_product_messages_content')))),
       (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'product_messages' AND index_name IN (
          'PRIMARY','ix_product_messages_thread','ix_product_messages_buyer','ix_product_messages_seller','ix_product_messages_sender'));
SHOW CREATE TABLE $rawSchema.product_messages;
"@
Write-Host 'Verifying the table definition. Enter the administrator password at the MySQL prompt.'
if ($VerifyOnly) {
    $verified = @(Invoke-Sql $admin $verification)
} else {
    $verified = @(Invoke-Sql $admin ((Get-Content -LiteralPath $migration -Raw -Encoding UTF8) + "`n" + $verification))
}
$expectedRows = if ($VerifyOnly) { '[0-9]+' } else { '0' }
if ($verified.Count -ne 2 -or $verified[0] -notmatch "^2024011324`t$expectedRows`t6`t5$") {
    throw 'Post-migration verification failed. Preserve the database and backup. Do not rerun creation.'
}
$verified | ForEach-Object { Write-Host $_ }

Write-Host 'Verifying SELECT and INSERT access with EXPLAIN. Enter the existing application database password.'
$null = Invoke-Sql $app @"
SELECT id FROM $rawSchema.product_messages LIMIT 0;
EXPLAIN INSERT INTO $rawSchema.product_messages (product_id, buyer_id, seller_id, sender_id, content)
VALUES (1, 1, 2, 1, 'privilege check only');
"@
Write-Host 'MESSAGE TABLE VERIFIED. No messages inserted. Backend is still running as before.'
