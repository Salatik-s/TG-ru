# TG-ru for iOS

Native fork of the complete official Telegram-iOS application. This workspace is separate
from Android and the family server. It is not a web-client wrapper.

Upstream and build tool versions are pinned in upstream-lock.json. The workflow is manual
only. No signing identity or Apple account belongs in CI. The default compile-only mode
uses explicit invalid dummy API values and produces BASELINE-NOT-CONNECTED.ipa, not a
connected client. The own-api mode requires your own TG_API_ID and TG_API_HASH repository
secrets; missing, dummy or malformed credentials stop before upstream checkout.

## Baseline build

Run build-support/build.py on an Apple Silicon Mac with Xcode 27.1 build 27A9275 and macOS 27.
The xcode-27 GitHub runner currently supplies that toolchain; each run verifies it.
The build fetches the exact upstream commit and submodules, verifies the source patches,
builds the complete //Telegram:Telegram arm64 target, and validates the IPA.
The build can take substantial time and disk. Private Actions consume the owner's budget;
the workflow timeout is 120 minutes and no automatic event launches it.

The resulting unsigned IPA requires local signing before installation. The Personal Team
variant disables extensions, Watch, Siri, iCloud and privileged entitlement requests.
Account storage is placed in Application Support/TG-ru in the app's private container;
passcode items use the existing private Keychain access group resolved from the actual
signing seed and bundle identifier. Secure Enclave and biometric access controls remain.
Application-container and Keychain compatibility must be verified on a real sideloaded phone;
an unsigned archive passing structural checks is not installation or login proof.
Background push and incoming calls are not promised for free weekly signing.

## Integration

Source directories transport/, access/, features/, family-core/ and family-ui/ are copied into the pinned checkout
only when present. Patches supplied by other agents are applied by the explicit
build-support/integration.json manifest. Unknown or unlisted scripts never run.
Every applied source patch and copied file is recorded in the build manifest.
The baseline build is not a complete protected FamilyGram client until gate and transport
integration is present and tested. Do not distribute it as a production replacement.

The user signs on their computer using the same bundle identifier and Apple account for
updates. Apple Personal Team profiles expire after seven days. Keep account credentials
out of repository files, workflow arguments, logs and build artifacts.

## Local checks

python build-support/test_build.py

These checks exercise source pin/patch preconditions, Personal Team storage patches,
unsigned configuration, credential validation and IPA layout validation without an Apple SDK.
They do not compile Swift. The separate macOS unit job runs swift test for family-core and
transport before any credentials or large upstream checkout are needed.

## Save your own API configuration on Windows

Run build-support/Configure-iOS.ps1. Both input values are hidden. The script validates their
format and writes only Windows DPAPI CurrentUser ciphertext beneath
%USERPROFILE%/.codex/private/tg-ru-ios/telegram-api.dpapi.json. The directory and file ACLs
allow only the current user and SYSTEM. No values are printed, sent to CI or stored in this
repository. -CheckOnly reports availability without printing values; -SelfTest exercises
DPAPI using synthetic values in memory. Uploading repository secrets is a separate step.
