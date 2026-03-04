# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Game Boy (DMG) emulator written in Kotlin for Android. The project implements cycle-accurate Game Boy hardware emulation including CPU (LR35902/modified Z80), PPU (Picture Processing Unit), APU (Audio Processing Unit), Timer, Joypad, and MBC1 memory bank controller.

## Architecture

### Module Structure

The project uses a multi-module architecture to separate concerns:

```
app/
├── gb-core-api/          # Platform-independent API interfaces
│   └── src/main/java/gb/core/api/
│       ├── GameBoyCore.kt       # Main emulator interface
│       ├── FrameResult.kt       # Frame output data
│       ├── InputState.kt        # Controller input state
│       └── CoreResult.kt        # Error handling types
│
├── gb-core-kotlin/       # Core emulation implementation
│   └── src/main/java/gb/core/impl/
│       ├── GameBoyCoreImpl.kt   # GameBoyCore implementation
│       └── cpu/
│           ├── Machine.kt       # Top-level machine orchestrator
│           ├── Cpu.kt           # CPU emulation (LR35902)
│           ├── Ppu.kt           # Graphics (PPU)
│           ├── Sound.kt         # Audio (APU with 4 channels)
│           ├── Timer.kt         # DIV/TIMA/TMA/TAC registers
│           ├── Joypad.kt        # Input handling
│           ├── SystemBus.kt     # Memory bus & I/O registers
│           ├── InterruptController.kt
│           ├── Registers.kt     # CPU register file
│           └── Mbc1.kt          # MBC1 cartridge controller
│
└── src/main/java/miyabi/kotlinandroidgameboy/
    ├── MainActivity.kt          # UI and display
    ├── InputStateHolder.kt      # Input state management
    └── emulator/
        ├── GameLoop.kt          # Frame timing loop
        └── CoreProvider.kt      # Dependency injection
```

**Key Design Principle**: The `gb-core-api` and `gb-core-kotlin` modules are Android-aware but UI-independent. The main `app` module handles UI (Jetpack Compose) and ties everything together.

### Execution Flow

1. **ROM Loading**: `GameBoyCoreImpl.loadRom()` → Creates `Machine` instance
2. **Frame Execution**: `runFrame()` executes ~70,224 CPU cycles per frame
3. **Component Stepping**: `Machine.stepInstruction()` orchestrates:
   - CPU instruction execution
   - Timer updates
   - PPU rendering
   - APU sample generation
   - Interrupt handling

### Critical Implementation Details

#### CPU Cycle Management
- Target: 70,224 cycles/frame (59.7 Hz)
- HALT state handling with PPU acceleration for efficiency
- Infinite loop detection with PC tracking

#### Memory Access Patterns
- PPU blocks VRAM during Mode 3 (Pixel Transfer)
- PPU blocks OAM during Mode 2 (OAM Search) and Mode 3
- DMA transfers should block CPU memory access (currently instant)

#### APU (Sound) Architecture
- 4 channels: Square1 (with sweep), Square2, Wave, Noise
- Frame sequencer at 512 Hz drives envelope, sweep, length counters
- Generates ~735 samples/frame for 44.1kHz output
- **Critical**: Wave channel period calculation and duty patterns have known accuracy issues (see GB_ACCURACY_ISSUES.md)

## Build & Development Commands

### Build the Project
```bash
./gradlew build
```

### Run on Device/Emulator
```bash
./gradlew installDebug
# Or use Android Studio's Run button
```

### Code Quality Tools

The project enforces code quality with ktlint and detekt on all modules:

```bash
# Format code with ktlint
./gradlew ktlintFormat

# Check code style
./gradlew ktlintCheck

# Run static analysis with detekt
./gradlew detekt
```

**Configuration**:
- ktlint: Applied via `build.gradle.kts` subprojects block
- detekt: Config in `detekt.yml` at project root

### Testing

```bash
# Run all unit tests
./gradlew test

# Run tests for a specific module
./gradlew :app:gb-core-kotlin:test
./gradlew :app:testDebugUnitTest

# Run Android instrumented tests
./gradlew connectedAndroidTest
```

### Clean Build
```bash
./gradlew clean
```

## Known Accuracy Issues

**IMPORTANT**: This emulator has several known deviations from real Game Boy hardware. Before making changes to CPU/PPU/APU/Timer code, consult these documents:

- `GB_ACCURACY_ISSUES.md` - Complete list of implementation differences vs real hardware, with priority ranking
- `PROGRESS.md` - Current implementation status and next steps
- `AUDIO_NOISE_ANALYSIS.md` - Known audio inaccuracies
- `SOUND_SPEC_COMPARISON.md` - APU implementation vs specification

### Critical Issues (Top Priority)

1. **Wave channel period is 8x wrong** (`Sound.kt:1083`) - Uses `* 16.0` instead of `* 2.0`
2. **Square wave duty pattern bit 3 inverted** (`Sound.kt:704-719`)
3. **Frame sequencer not synced to DIV register** (`Sound.kt:82-83`)
4. **HALT bug incomplete** (`Cpu.kt:133-143`) - Only checks IF, should check `(IE & IF)`
5. **HALT/STOP clock progression inaccurate** (`Machine.kt:43-56`)

When modifying sound, timing, or CPU code, verify changes against the accuracy issues list.

## Testing with Test ROMs

The emulator should be validated against standard test ROMs:

- **blargg's test ROMs**: `cpu_instrs.gb`, `instr_timing.gb`, `mem_timing.gb`, `dmg_sound.gb`
- **mooneye-gb**: acceptance tests in `acceptance/` directory
- **Commercial games**: Pokémon Red/Green (MBC1, timer, sound), Super Mario Land (PPU timing), Tetris (basic operation)

Place test ROMs in device storage and load via the file picker UI.

## Code Conventions

### Unsigned Integers
The codebase extensively uses Kotlin's `UByte` and `UShort` types to match Game Boy's 8-bit and 16-bit unsigned architecture:

```kotlin
@OptIn(ExperimentalUnsignedTypes::class)
fun readByte(address: UShort): UByte
```

Always annotate functions/classes using unsigned types with `@OptIn(ExperimentalUnsignedTypes::class)`.

### Register Access
CPU registers are accessed via the `Registers` class:
```kotlin
registers.a  // Accumulator (UByte)
registers.pc // Program counter (UShort)
registers.flagZ  // Zero flag (Boolean)
```

### Cycle-Accurate Timing
All components step forward by CPU cycles:
```kotlin
fun step(cycles: Int) {
    // Update component state for the given cycle count
}
```

Return cycle counts using the `Cycles` object constants or create new ones:
```kotlin
return Cycles.LD_R_R  // 4 cycles
return Cycles.create(12)  // Custom cycle count
```

### Memory-Mapped I/O
I/O registers are accessed through `SystemBus`:
```kotlin
bus.readByte(0xFF00u.toUShort())  // Read JOYP register
bus.writeByte(0xFF40u.toUShort(), value)  // Write LCDC register
```

## Logging & Debugging

The emulator uses Android's `android.util.Log`:
```kotlin
android.util.Log.d("GameBoyCore", "Frame $frameIndex completed")
android.util.Log.e("Cpu", "Unknown opcode: 0x${opcode.toString(16)}")
```

Logging is throttled (every 3000 frames) for performance. Temporarily increase logging frequency when debugging specific issues.

### Infinite Loop Detection
PC stuck detection triggers after 101 iterations:
```kotlin
if (pcStuckCount == 101) {
    // Detailed state logging
}
```

When debugging game lockups, check these logs for register state.

## Common Development Patterns

### Adding a New CPU Instruction
1. Add opcode case in `Cpu.executeByOpcode()`
2. Implement logic using `registers` and `bus`
3. Return correct cycle count from `Cycles` object
4. Update flags (Z, N, H, C) if applicable

### Adding a New I/O Register
1. Add address constant in `SystemBus`
2. Implement read/write cases in `readByte()`/`writeByte()`
3. Update corresponding component (`Ppu`, `Sound`, `Timer`, etc.)

### Modifying PPU Rendering
- Background colors are stored in `bgColorIds` array for sprite priority
- Sprite limit: 10 per scanline, 40 total
- Current sprite ordering issue: uses X-coordinate descending instead of OAM index ascending (see PROGRESS.md)

## Performance Considerations

- Frame execution target: ~16.7ms (60 fps) but emulates 59.7 Hz Game Boy
- HALT state optimization: Accelerates PPU when CPU is idle
- Audio sample generation: ~735 samples per frame
- Logging is throttled to every 3000 frames to reduce overhead

## File Naming Conventions

- Core emulation: PascalCase (e.g., `GameBoyCoreImpl.kt`, `SystemBus.kt`)
- Android UI: PascalCase for activities/composables
- Documentation: SCREAMING_SNAKE_CASE.md for technical docs

## External Resources

When implementing accuracy fixes, refer to:
- Pan Docs: https://gbdev.io/pandocs/
- Game Boy CPU Manual
- TCAGBD (The Cycle-Accurate Game Boy Docs)
- blargg's test ROMs documentation
