# Deterministic resolution of reviewed, additive merge conflicts.
# Larger rewrites are resolved separately and checked by the Kotlin compiler.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$choices = @{
    'app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt' = @('ours', 'theirs', 'theirs', 'theirs', 'both')
    'app/src/main/java/com/music/bitchord/ui/MainViewModel.kt' = @('home', 'methods', 'ours')
    '.gitignore' = @('both')
    'app/build.gradle.kts' = @('theirs')
    'app/src/main/java/com/music/bitchord/BitChordApplication.kt' = @('theirs')
    'app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt' = @('theirs')
    'app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt' = @('both')
    'app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt' = @('theirs')
    'app/src/main/java/com/music/bitchord/ui/components/SpotifyImportAlert.kt' = @('ours')
    'app/src/main/java/com/music/bitchord/ui/screens/EqualizerScreen.kt' = @('both', 'ours', 'ours')
    'app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt' = @('theirs')
    'app/src/test/java/com/music/bitchord/EqualizerProcessorTest.kt' = @('both')
}
$files = @(git diff --name-only --diff-filter=U)
foreach ($file in $files) {
    $isStrings = $file -like 'app/src/main/res/*/strings.xml'
    if (!$isStrings -and !$choices.ContainsKey($file)) { continue }
    $lines = [System.IO.File]::ReadAllLines((Join-Path $PWD $file))
    $output = New-Object 'System.Collections.Generic.List[string]'
    $ours = New-Object 'System.Collections.Generic.List[string]'
    $theirs = New-Object 'System.Collections.Generic.List[string]'
    $state = 'normal'
    $index = 0
    foreach ($line in $lines) {
        if ($line.StartsWith('<<<<<<<')) {
            if ($state -ne 'normal') { throw 'Nested conflict' }
            $ours.Clear(); $theirs.Clear(); $state = 'ours'; continue
        }
        if ($line -eq '=======') { $state = 'theirs'; continue }
        if ($line.StartsWith('>>>>>>>')) {
            $choice = if ($isStrings) { 'both' } else { $choices[$file][$index] }
            if ($choice -eq 'home') {
                $output.AddRange($ours)
                $output.Add('        loadTopPicks(identity, rebuild = false)')
            }
            if ($choice -eq 'methods') {
                $output.AddRange($ours)
                $output.Add('    }')
                $output.Add('')
                $output.Add('    /**')
                $output.AddRange($theirs)
            }
            if ($choice -in @('ours', 'both')) { $output.AddRange($ours) }
            if ($choice -in @('theirs', 'both')) { $output.AddRange($theirs) }
            if ($choice -notin @('ours', 'theirs', 'both', 'home', 'methods')) { throw "Missing resolution: $file conflict $index" }
            $index++; $state = 'normal'; continue
        }
        switch ($state) {
            'ours' { $ours.Add($line) }
            'theirs' { $theirs.Add($line) }
            'normal' { $output.Add($line) }
        }
    }
    if ($state -ne 'normal') { throw "Unterminated conflict in $file" }
    if (!$isStrings -and $index -ne $choices[$file].Count) { throw "Unexpected conflict count in $file" }
    # Both branches added bit_perfect_output; keep one resource definition.
    if ($isStrings) {
        $seen = @{}
        $output = @($output | Where-Object {
            if ($_ -match '<string name="([^"]+)"') {
                $name = $Matches[1]
                if ($seen.ContainsKey($name)) { return $false }
                $seen[$name] = $true
            }
            return $true
        })
    }
    [System.IO.File]::WriteAllLines((Join-Path $PWD $file), $output, $utf8)
    git add -- $file
    Write-Output "Resolved $file ($index conflicts)"
}
