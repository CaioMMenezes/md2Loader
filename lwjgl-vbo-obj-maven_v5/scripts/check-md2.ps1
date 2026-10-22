param([switch]$Render)
$ErrorActionPreference = 'Stop'

# Compile com os mesmos JARs do pom.xml, já presentes no cache Maven local.
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $repository = Join-Path $env:USERPROFILE '.m2/repository/org/lwjgl'
    [xml]$projectPom = Get-Content -LiteralPath 'pom.xml' -Raw
    $version = $projectPom.project.properties.'lwjgl.version'
    $jars = @()
    foreach ($module in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
        foreach ($classifier in @('', '-natives-windows')) {
            $jarPath = Join-Path $repository "$module/$version/$module-$version$classifier.jar"
            if (!(Test-Path -LiteralPath $jarPath)) {
                throw "Dependência ausente: $jarPath. Resolva o projeto com Maven antes de executar este script."
            }
            $jars += $jarPath
        }
    }
    $dependencies = $jars -join [IO.Path]::PathSeparator
    $sources = @(Get-ChildItem src/main/java, src/test/java -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
    New-Item -ItemType Directory -Path target/md2-checks -Force | Out-Null
    & javac --release 17 -encoding UTF-8 -cp $dependencies -d target/md2-checks @sources
    if ($LASTEXITCODE -ne 0) { throw 'Falha na compilação' }

    # Sem -Render, todos os testes são de CPU. -Render cria um contexto OpenGL em janela oculta.
    $testArguments = @()
    if ($Render) { $testArguments += '--render' }
    & java --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp "target/md2-checks$([IO.Path]::PathSeparator)$dependencies" aula.md2.Md2Checks @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'Falha nas verificações MD2' }
} finally {
    Pop-Location
}
