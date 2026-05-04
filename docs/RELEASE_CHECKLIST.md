# Release Checklist

Use this checklist before publishing a GitHub release.

## Current Build

- Version: `v0.0.4`
- APK: `app/build/outputs/apk/debug/ApkClaw_v0.0.4_20260504_084846.apk`
- SHA256: `DE087A0E9BADA6E3788C8B9F4CF1FF76894F5FF1F88BDAD910EF702C8AFBB1E9`
- Size: `53,239,523` bytes

## Steps

1. Run a clean debug build:

   ```powershell
   .\build-debug.bat
   ```

2. Confirm the generated APK filename contains the expected version.
3. Create a GitHub Release named `v0.0.4`.
4. Upload the APK as a Release asset.
5. Paste the SHA256 into the release notes.
6. Mention that this is an unofficial derivative of `apkclaw-team/ApkClaw`.
7. Link back to the upstream project:

   ```text
   https://github.com/apkclaw-team/ApkClaw
   ```

8. Confirm no local secrets are tracked:

   ```powershell
   git status --short --untracked-files=all
   git ls-files | Select-String -Pattern 'local.properties|\.jks|keystore|token|secret|apikey|api_key'
   ```

## Suggested Release Notes

```markdown
## ApkClaw Personal Enhanced Edition v0.0.4

This is an unofficial derivative of apkclaw-team/ApkClaw.
Original project: https://github.com/apkclaw-team/ApkClaw

### Highlights

- Added local Session & Memory management
- Added global memory and global prompt controls
- Added floating pause/resume/follow-up workflow
- Added configurable max iterations and wait timing
- Added DeepSeek V4 thinking-mode compatibility
- Added reproducible Windows debug build scripts

### APK

File: ApkClaw_v0.0.4_20260504_084846.apk
SHA256: DE087A0E9BADA6E3788C8B9F4CF1FF76894F5FF1F88BDAD910EF702C8AFBB1E9

### Notes

Use only on devices and accounts you own or are authorized to operate.
This project is provided under Apache-2.0 on an AS IS basis.
```
