# Скачивает дополнительные модели Essentia (CC BY-NC-SA 4.0) в analyzer\models для сборки на NAS без интернета.
$ErrorActionPreference = "Stop"
$dir = Join-Path $PSScriptRoot "models"
New-Item -ItemType Directory -Force $dir | Out-Null
$base = "https://essentia.upf.edu/models/classification-heads"
foreach ($head in @("mood_acoustic/mood_acoustic-discogs-effnet-1", "mood_electronic/mood_electronic-discogs-effnet-1")) {
    foreach ($ext in @(".pb", ".json")) {
        $name = Split-Path $head -Leaf
        Invoke-WebRequest "$base/$head$ext" -OutFile (Join-Path $dir "$name$ext")
    }
}
Get-ChildItem $dir
