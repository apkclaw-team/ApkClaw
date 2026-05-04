# ApkClaw Project Guide

## Project Introduction

ApkClaw is an Android-native AI automation application. It accepts natural-language instructions from messaging channels such as DingTalk, Feishu, QQ, Discord, Telegram, and WeChat, then uses an LLM-driven agent plus Android accessibility APIs to operate the device.

The current project goal is no longer just to prove that an Android agent can click around. The practical goal is to make the app usable in repeated real workflows: configurable, debuggable, recoverable after interruption, and able to preserve the right amount of local memory without letting stale context corrupt new tasks.

## Core Architecture

The current execution chain is:

1. `ChannelManager` receives user messages from external channels.
2. `TaskOrchestrator` serializes task execution and manages lifecycle transitions.
3. `AgentService` / `DefaultAgentService` runs the observe-think-act loop.
4. `ToolRegistry` dispatches tool calls to device-facing tool implementations.
5. `ClawAccessibilityService` performs actual gestures, node traversal, key injection, and screenshots.
6. `SessionMemoryManager` manages persisted session context, condensed memory, global memory, and global prompt injection.
7. `AppViewModel` builds the effective `AgentConfig` from current user settings and pushes updates into the running agent service.

## Current Product Position

This project already supports:

1. Local LLM configuration.
2. Configurable maximum iteration count.
3. Multi-session conversation and local memory persistence.
4. Session-specific and global prompt injection.
5. Floating pause / resume / follow-up interaction.
6. Wait timing configuration with global scaling.
7. Reproducible Windows debug builds via project scripts.

The active work direction is incremental hardening rather than foundational rewrites.

## Development Journey

The recent development path can be understood as a sequence of practical bottlenecks that were fixed one by one.

### Phase 1: Build Reproducibility

The first major issue was that debug builds on Windows were fragile and environment-dependent. The project now includes repo-owned build scripts so that Gradle always runs with a valid Android Studio JBR instead of relying on ad hoc local shell commands.

### Phase 2: Runtime Configuration Instead of Hard-Coding

Early runtime behavior such as iteration count and wait suggestions was overly static. The project moved these settings into the application UI so the effective agent configuration can be changed without editing code every time.

### Phase 3: Session and Memory Foundation

The next bottleneck was continuity. Tasks could run, but there was no strong structure for preserving useful conversational state across runs. The project introduced:

1. Session context.
2. Condensed session memory.
3. Global memory.
4. Global prompt.
5. Multi-session switching and editing.

This phase also clarified the boundary between persisted session context and transient in-flight runtime state.

### Phase 4: Pause and Mid-Task Correction

Users needed a way to correct a running task without always killing it. The project added pause/follow-up behavior and later replaced the earlier activity-based pause UI with a floating overlay so the original device page can remain visible.

### Phase 5: Wait Timing Strategy Cleanup

Wait timing started as advice embedded in prompt text. That was not enough. It is now backed by stored settings, injected dynamically into the system prompt, and partially sanitized out of old session records so outdated timing advice does not keep leaking into future runs.

### Phase 6: Long-Use UX Corrections

Several issues that looked minor in isolation turned out to matter in repeated daily usage:

1. Chat auto-scroll behavior.
2. Button order and wording.
3. Mixed Chinese/English strings.
4. Pause overlay layout and feedback.
5. Missing summary writes on cancellation.

These were treated as functional usability issues, not just polish.

## Main Problems and Solution Routes

### Problem 1: Builds were not reliably reproducible

Route:

1. Detect JBR automatically.
2. Pin Gradle to that runtime.
3. Keep build entry points inside the repo.

### Problem 2: Agent runtime behavior was too rigid

Route:

1. Move runtime knobs into settings.
2. Regenerate `AgentConfig` dynamically.
3. Push updates through `AppViewModel -> TaskOrchestrator -> AgentService`.

### Problem 3: Session continuity and memory were underpowered

Route:

1. Persist session messages locally.
2. Derive transcript and condensed memory.
3. Separate session context from global memory and global prompt.
4. Support edit, preview, delete, and session switching flows.

### Problem 4: Old memory polluted new tasks

Route:

1. Keep current wait strategy in system prompt, not in historical notes.
2. Sanitize injected session content before appending it to the task prompt.
3. Treat historical timing guidance as stale and lower its authority.

### Problem 5: Mid-task correction was too expensive

Route:

1. Support pause instead of only cancellation.
2. Allow follow-up instructions to enqueue into the running agent.
3. Distinguish pause-and-continue from stop-and-restart semantics.

### Problem 6: Users needed clearer task feedback

Route:

1. Improve floating feedback and button wording.
2. Write cancellation outcomes into condensed memory.
3. Tighten chat and settings page behavior around repeated use.

## Important Behavioral Decisions

### Persisted Context vs Runtime State

Persisted session context can be injected into future tasks. In-flight runtime state cannot. This is intentional. A new task should inherit saved conversation context, but it should not silently resume a half-finished tool chain from the middle.

### New Task vs Follow-Up During a Running Task

If a task is already running, follow-up instructions are enqueued into the current agent run. If the task is stopped first, the next message becomes a new task and only reads persisted context.

### Home Reset Before New Task

The current task startup path still resets device state with a Home press before running a new task. This improves predictability but can be revisited later if a more contextual start policy is needed.

## Key Files

Use these as primary anchors when changing behavior:

1. `app/src/main/java/com/apk/claw/android/TaskOrchestrator.kt`
2. `app/src/main/java/com/apk/claw/android/AppViewModel.kt`
3. `app/src/main/java/com/apk/claw/android/agent/AgentConfig.kt`
4. `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt`
5. `app/src/main/java/com/apk/claw/android/session/SessionMemoryManager.kt`
6. `app/src/main/java/com/apk/claw/android/service/ClawAccessibilityService.java`
7. `app/src/main/java/com/apk/claw/android/ui/settings/SessionChatActivity.kt`
8. `app/src/main/java/com/apk/claw/android/ui/settings/WaitTimingSettingsActivity.kt`
9. `app/src/main/java/com/apk/claw/android/floating/FloatingCircleManager.kt`
10. `app/build.gradle.kts`

## Working Principles for Future Changes

1. Prefer changing root behavior instead of stacking more prompt wording on top of broken logic.
2. Treat persisted memory contamination as a systems problem, not just a prompt-writing problem.
3. Keep session features and memory features independently controllable.
4. When a user reports a UX issue in chat, first trace the controlling lifecycle path before redesigning UI text.
5. Preserve reproducible build entry points whenever Android build configuration changes.
6. Validate changes with at least file-level diagnostics and preferably a full build.

## Build and Verification

Preferred local validation path:

1. Use `build-debug.bat` on Windows.
2. Confirm the generated APK name includes the expected version.
3. For UI-only resource changes, still run at least one full Android build.

## Current State Summary

The project is beyond the prototype stage but still in active product hardening. The most important theme is reducing the gap between “the agent can do something once” and “the user can trust it repeatedly, interrupt it safely, and continue from sensible context”.
