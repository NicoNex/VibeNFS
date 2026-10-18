.PHONY: apk debug bundle release test install install-debug reinstall
apk:
	./gradlew assembleRelease
	cp app/build/outputs/apk/release/app-release.apk vibenfs.apk

debug:
	./gradlew assembleDebug
	cp app/build/outputs/apk/debug/app-debug.apk vibenfs-debug.apk

test:
	./gradlew testDebugUnitTest

# Straight onto whatever phone is plugged in. Gradle's installDebug picks a device at random
# when an emulator is also up, so these go through adb with the device named.
#
# adb answers a key mismatch with INSTALL_FAILED_UPDATE_INCOMPATIBLE and nothing else, so the
# targets explain that one cause themselves; any other adb error is shown as is.
define adb_install
out=$$(adb -d install -r $(1) 2>&1); rc=$$?; echo "$$out"; \
if [ $$rc -ne 0 ] && echo "$$out" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then \
echo; \
echo "The phone holds a build signed by a different key - a download from GitHub, most likely."; \
echo "No local build can update it. Run: make reinstall (your saved network folders will need re-adding)."; \
fi; exit $$rc
endef

install:
	./gradlew assembleRelease
	@$(call adb_install,app/build/outputs/apk/release/app-release.apk)

install-debug:
	./gradlew assembleDebug
	@$(call adb_install,app/build/outputs/apk/debug/app-debug.apk)

# The way out of a signature mismatch: uninstall, which drops the saved folder list. Files on
# the NFS servers are never touched. Asks first, because there is no undo.
reinstall:
	./gradlew assembleRelease
	@printf 'This uninstalls dev.vibenfs from the phone and DELETES its saved network folders. Type yes to go on: '; \
	read answer; [ "$$answer" = yes ] || { echo "Nothing done."; exit 1; }
	adb -d uninstall dev.vibenfs
	adb -d install app/build/outputs/apk/release/app-release.apk

# Play rejects debug-signed uploads, so refuse to build a bundle without the upload key.
bundle:
	@test -f keystore.properties || { echo "keystore.properties missing"; exit 1; }
	./gradlew bundleRelease
	cp app/build/outputs/bundle/release/app-release.aab vibenfs.aab

# New Play release: bump VERSION_CODE, then build the bundle.
# ponytail: bump precedes build; a failed build burns a code number, but Play allows gaps.
# versionName stays manual - edit VERSION_NAME in gradle.properties for a semantic bump.
release:
	@test -f keystore.properties || { echo "keystore.properties missing"; exit 1; }
	@before=$$(awk -F= '/^VERSION_CODE=/{print $$2}' gradle.properties); \
	awk -F= -v OFS== '/^VERSION_CODE=/{$$2=$$2+1} 1' gradle.properties > gradle.properties.tmp && mv gradle.properties.tmp gradle.properties; \
	[ "$$(awk -F= '/^VERSION_CODE=/{print $$2}' gradle.properties)" != "$$before" ] || { echo "VERSION_CODE= line not found in gradle.properties"; exit 1; }
	@echo "VERSION_CODE -> $$(awk -F= '/^VERSION_CODE=/{print $$2}' gradle.properties), VERSION_NAME $$(awk -F= '/^VERSION_NAME=/{print $$2}' gradle.properties)"
	$(MAKE) bundle
