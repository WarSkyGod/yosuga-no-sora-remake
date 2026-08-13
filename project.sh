#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

usage() {
	cat <<'EOF'
Usage:
  ./project.sh run macos-sdl2 [engine options...]

Commands:
  run macos-sdl2  Incrementally build and run the macOS SDL2 version.

The development build reads the repository's data directory directly. It does
not copy game assets into an application bundle.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
	usage
	exit 0
fi

if [[ $# -lt 2 ]]; then
	usage >&2
	exit 2
fi

command_name="$1"
target_name="$2"
shift 2

if [[ "$command_name" != "run" || "$target_name" != "macos-sdl2" ]]; then
	echo "Unsupported command: $command_name $target_name" >&2
	usage >&2
	exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
	echo "macos-sdl2 can only be run on macOS." >&2
	exit 1
fi

data_dir="$PROJECT_ROOT/data"
build_dir="$PROJECT_ROOT/build/dev/macos-sdl2"
executable="$build_dir/krkrsdl2"

cmake_command="${CMAKE_COMMAND:-}"
if [[ -z "$cmake_command" ]] && command -v cmake >/dev/null 2>&1; then
	cmake_command="cmake"
elif [[ -z "$cmake_command" && -f "$build_dir/CMakeCache.txt" ]]; then
	cmake_command="$(sed -n 's/^CMAKE_COMMAND:INTERNAL=//p' "$build_dir/CMakeCache.txt" | head -n 1)"
elif [[ -z "$cmake_command" && -x "/Applications/CMake.app/Contents/bin/cmake" ]]; then
	cmake_command="/Applications/CMake.app/Contents/bin/cmake"
fi

if ! command -v "$cmake_command" >/dev/null 2>&1; then
	echo "CMake is required to build the macOS SDL2 version." >&2
	echo "Install CMake or set CMAKE_COMMAND to its executable path." >&2
	exit 1
fi

if [[ ! -f "$data_dir/startup.tjs" ]]; then
	echo "Game data is incomplete: $data_dir/startup.tjs was not found." >&2
	exit 1
fi

if [[ ! -f "$build_dir/CMakeCache.txt" ]] ||
	! grep -q '^KRKRSDL2_GENERATE_CONTENT_MANIFEST:BOOL=OFF$' "$build_dir/CMakeCache.txt"; then
	echo "Configuring the macOS SDL2 development build..."
	"$cmake_command" \
		-S "$PROJECT_ROOT" \
		-B "$build_dir" \
		-DOPTION_BUILD_MACOS_BUNDLE=OFF \
		-DKRKRSDL2_GENERATE_CONTENT_MANIFEST=OFF \
		-DCMAKE_BUILD_TYPE=RelWithDebInfo
fi

echo "Building the macOS SDL2 development executable..."
"$cmake_command" --build "$build_dir" --target krkrsdl2 --parallel

if [[ ! -x "$executable" ]]; then
	echo "Build completed, but the executable was not found: $executable" >&2
	exit 1
fi

echo "Starting macOS SDL2 with data from: $data_dir"
exec "$executable" "$@" "$data_dir"
