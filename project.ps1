Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:gameExitCode = 0

$scriptArguments = @($args)
$commandName = if ($scriptArguments.Count -ge 1) { $scriptArguments[0] } else { "" }
$platformTarget = if ($scriptArguments.Count -ge 2) { $scriptArguments[1] } else { "" }
$engineArguments = if ($scriptArguments.Count -gt 2) {
	@($scriptArguments[2..($scriptArguments.Count - 1)])
} else {
	@()
}

function Show-Usage {
	Write-Host @"
Usage:
  .\project.ps1 run windows-krkrz [engine options...]
  .\project.ps1 run windows-sdl2 [engine options...]

Commands:
  run windows-krkrz  Run the prebuilt native Windows KRKRZ version.
  run windows-sdl2   Incrementally build and run the Windows SDL2 version.

Both development launchers read the repository's data directory directly and
do not copy game assets.
"@
}

function Invoke-NativeCommand {
	param(
		[Parameter(Mandatory = $true)]
		[string] $Executable,

		[Parameter(Mandatory = $true)]
		[string[]] $Arguments
	)

	& $Executable @Arguments
	if ($LASTEXITCODE -ne 0) {
		throw "Command failed with exit code ${LASTEXITCODE}: $Executable"
	}
}

function Find-CMake {
	param(
		[Parameter(Mandatory = $true)]
		[string] $BuildDirectory
	)

	$cmake = Get-Command "cmake" -ErrorAction SilentlyContinue
	if ($null -ne $cmake) {
		return $cmake.Path
	}

	$cache = Join-Path $BuildDirectory "CMakeCache.txt"
	if (Test-Path -LiteralPath $cache -PathType Leaf) {
		$match = Select-String -LiteralPath $cache `
			-Pattern '^CMAKE_COMMAND:INTERNAL=(.+)$' | Select-Object -First 1
		if ($null -ne $match) {
			$cachedCMake = $match.Matches[0].Groups[1].Value
			if (Test-Path -LiteralPath $cachedCMake -PathType Leaf) {
				return $cachedCMake
			}
		}
	}

	return $null
}

function Start-Game {
	param(
		[Parameter(Mandatory = $true)]
		[string] $Executable,

		[Parameter(Mandatory = $true)]
		[string] $WorkingDirectory,

		[Parameter(Mandatory = $true)]
		[string] $DataDirectory,

		[string[]] $Arguments = @()
	)

	Write-Host "Starting $Executable with data from: $DataDirectory"
	Push-Location $WorkingDirectory
	try {
		& $Executable @Arguments $DataDirectory
		$script:gameExitCode = $LASTEXITCODE
	}
	finally {
		Pop-Location
	}
}

function Start-WindowsKrkrz {
	param(
		[Parameter(Mandatory = $true)]
		[string] $ProjectRoot,

		[Parameter(Mandatory = $true)]
		[string] $DataDirectory,

		[string[]] $Arguments = @()
	)

	$runtimeDirectory = Join-Path $ProjectRoot "platform\windows-krkrz"
	$executable = Join-Path $runtimeDirectory "tvpwin32.exe"
	if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
		throw "KRKRZ executable was not found: $executable"
	}

	Start-Game $executable $runtimeDirectory $DataDirectory $Arguments
}

function Start-WindowsSdl2 {
	param(
		[Parameter(Mandatory = $true)]
		[string] $ProjectRoot,

		[Parameter(Mandatory = $true)]
		[string] $DataDirectory,

		[string[]] $Arguments = @()
	)

	$buildDirectory = Join-Path $ProjectRoot "build\dev\windows-sdl2"
	$cmake = Find-CMake $buildDirectory
	if ([string]::IsNullOrWhiteSpace($cmake)) {
		throw "CMake is required to build windows-sdl2. Install CMake and a Visual Studio C++ toolchain."
	}

	$cache = Join-Path $buildDirectory "CMakeCache.txt"
	$manifestGenerationDisabled = $false
	if (Test-Path -LiteralPath $cache -PathType Leaf) {
		$manifestGenerationDisabled = Select-String -LiteralPath $cache `
			-Pattern '^KRKRSDL2_GENERATE_CONTENT_MANIFEST:BOOL=OFF$' -Quiet
	}
	if (-not $manifestGenerationDisabled) {
		Write-Host "Configuring the Windows SDL2 development build..."
		Invoke-NativeCommand $cmake @(
			"-S", $ProjectRoot,
			"-B", $buildDirectory,
			"-DKRKRSDL2_GENERATE_CONTENT_MANIFEST=OFF",
			"-DCMAKE_BUILD_TYPE=RelWithDebInfo"
		)
	}

	Write-Host "Building the Windows SDL2 development executable..."
	Invoke-NativeCommand $cmake @(
		"--build", $buildDirectory,
		"--target", "krkrsdl2",
		"--config", "RelWithDebInfo",
		"--parallel"
	)

	$executableCandidates = @(
		(Join-Path $buildDirectory "RelWithDebInfo\tvpwin64.exe"),
		(Join-Path $buildDirectory "RelWithDebInfo\tvpwin32.exe"),
		(Join-Path $buildDirectory "tvpwin64.exe"),
		(Join-Path $buildDirectory "tvpwin32.exe")
	)
	$executable = $executableCandidates |
		Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
		Select-Object -First 1
	if ([string]::IsNullOrWhiteSpace($executable)) {
		throw "Build completed, but tvpwin64.exe or tvpwin32.exe was not found in: $buildDirectory"
	}

	Start-Game $executable (Split-Path -Parent $executable) $DataDirectory $Arguments
}

if ($commandName -eq "-h" -or $commandName -eq "--help") {
	Show-Usage
	exit 0
}

if ([string]::IsNullOrWhiteSpace($commandName) -or
	[string]::IsNullOrWhiteSpace($platformTarget)) {
	Show-Usage
	exit 2
}

if ($commandName -ne "run" -or
	$platformTarget -notin @("windows-krkrz", "windows-sdl2")) {
	Write-Host "Unsupported command: $commandName $platformTarget" -ForegroundColor Red
	Show-Usage
	exit 2
}

if ($env:OS -ne "Windows_NT") {
	Write-Error "$platformTarget can only be run on Windows."
	exit 1
}

$projectRoot = $PSScriptRoot
$dataDirectory = Join-Path $projectRoot "data"
$startupScript = Join-Path $dataDirectory "startup.tjs"
if (-not (Test-Path -LiteralPath $startupScript -PathType Leaf)) {
	Write-Error "Game data is incomplete: $startupScript was not found."
	exit 1
}
$resolvedDataDirectory = (Resolve-Path -LiteralPath $dataDirectory).Path

try {
	if ($platformTarget -eq "windows-krkrz") {
		Start-WindowsKrkrz $projectRoot $resolvedDataDirectory $engineArguments
	} else {
		Start-WindowsSdl2 $projectRoot $resolvedDataDirectory $engineArguments
	}
}
catch {
	Write-Error $_
	exit 1
}

exit $script:gameExitCode
