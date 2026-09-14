param(
    [string] $Root = "."
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$rootPath = (Resolve-Path -LiteralPath $Root).Path
$linkPattern = [regex]'!?\[[^\]]+\]\((?<target><[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)'
$ignoredSchemes = @(
    "http:",
    "https:",
    "mailto:",
    "tel:",
    "data:",
    "javascript:"
)

function Get-MarkdownTargetPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Target
    )

    $trimmed = $Target.Trim()

    if ($trimmed.StartsWith("<") -and $trimmed.Contains(">")) {
        $end = $trimmed.IndexOf(">")
        $trimmed = $trimmed.Substring(1, $end - 1)
    }

    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        return $null
    }

    if ($trimmed.StartsWith("#")) {
        return $null
    }

    foreach ($scheme in $ignoredSchemes) {
        if ($trimmed.StartsWith($scheme, [StringComparison]::OrdinalIgnoreCase)) {
            return $null
        }
    }

    $withoutFragment = $trimmed.Split("#", 2)[0]
    if ([string]::IsNullOrWhiteSpace($withoutFragment)) {
        return $null
    }

    return [Uri]::UnescapeDataString($withoutFragment)
}

$markdownFiles = Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter "*.md" |
    Where-Object { $_.FullName -notlike "*\.git\*" }

function Test-InInlineCode {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Line,
        [Parameter(Mandatory = $true)]
        [int] $Index
    )

    $beforeMatch = $Line.Substring(0, $Index)
    $backticks = @($beforeMatch.ToCharArray() | Where-Object { $_ -eq '`' }).Count
    return ($backticks % 2) -eq 1
}

$brokenLinks = New-Object System.Collections.Generic.List[object]
$totalLinks = 0

foreach ($file in $markdownFiles) {
    $lines = Get-Content -LiteralPath $file.FullName -Encoding UTF8
    $insideFence = $false

    for ($lineIndex = 0; $lineIndex -lt $lines.Count; $lineIndex++) {
        $line = $lines[$lineIndex]
        if ($line.TrimStart().StartsWith('```')) {
            $insideFence = -not $insideFence
            continue
        }

        if ($insideFence) {
            continue
        }

        foreach ($match in $linkPattern.Matches($line)) {
            if (Test-InInlineCode -Line $line -Index $match.Index) {
                continue
            }

            $targetPath = Get-MarkdownTargetPath -Target $match.Groups["target"].Value
            if ($null -eq $targetPath) {
                continue
            }

            $totalLinks++
            $absoluteTarget = [IO.Path]::GetFullPath((Join-Path -Path $file.DirectoryName -ChildPath $targetPath))

            if (-not (Test-Path -LiteralPath $absoluteTarget)) {
                $brokenLinks.Add([pscustomobject]@{
                    File = $file.FullName.Substring($rootPath.Length + 1)
                    Line = $lineIndex + 1
                    Target = $match.Groups["target"].Value
                    ResolvedPath = $absoluteTarget
                })
            }
        }
    }
}

Write-Host "markdown_files=$($markdownFiles.Count)"
Write-Host "relative_links=$totalLinks"
Write-Host "broken_links=$($brokenLinks.Count)"

foreach ($brokenLink in $brokenLinks) {
    Write-Host "$($brokenLink.File):$($brokenLink.Line) -> $($brokenLink.Target)"
}

if ($brokenLinks.Count -gt 0) {
    exit 1
}
