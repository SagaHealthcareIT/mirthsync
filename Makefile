VERSION := 3.6.0
NPM_VERSION := $(VERSION)

default:
	echo "use lein directly or read the README.md file"

# Build the uberjar only (no tests, faster for development)
build:
	lein do clean, uberjar

# Prepare npm package directory with JAR file
npm-prepare: build
	@echo "Preparing npm package..."
	mkdir -p pkg/npm/lib
	cp target/uberjar/mirthsync-$(VERSION)-standalone.jar pkg/npm/lib/mirthsync.jar
	@echo "Updating npm package version to $(NPM_VERSION)..."
	cd pkg/npm && npm version $(NPM_VERSION) --no-git-tag-version --allow-same-version
	@echo "npm package prepared in pkg/npm/"

# Build npm package tarball for local testing
npm-pack: npm-prepare
	@echo "Creating npm package tarball..."
	cd pkg/npm && npm pack
	mv pkg/npm/*.tgz target/
	@echo "Package created: target/saga-it-mirthsync-$(NPM_VERSION).tgz"

# Test npm package locally (install globally from tarball)
npm-test-install: npm-pack
	@echo "Installing npm package globally for testing..."
	npm install -g target/saga-it-mirthsync-$(NPM_VERSION).tgz
	@echo "Testing mirthsync command..."
	mirthsync -h

# Publish to npm (requires npm login)
npm-publish: npm-prepare
	@echo "Publishing to npm..."
	cd pkg/npm && npm publish --access public
	@echo "Published @saga-it/mirthsync@$(NPM_VERSION) to npm"

# Clean npm artifacts
npm-clean:
	rm -rf pkg/npm/lib/mirthsync.jar
	rm -f pkg/npm/*.tgz
	rm -f target/*.tgz

release:
	sed -E -i.bak "s/(defproject com\\.saga-it\\/mirthsync) \"[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?\"/\\1 \"$(VERSION)\"/g" project.clj
	rm -f project.clj.bak
	sed -E -i.bak "s/(version of mirthSync is) \"[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?\"/\\1 \"$(VERSION)\"/g" README.md
	rm -f README.md.bak
	sed -E -i.bak "s/[0-9]+\\.[0-9]+\\.[0-9]+(-SNAPSHOT)?/$(VERSION)/g" pkg/mirthsync.sh pkg/mirthsync.bat
	rm -f pkg/mirthsync.sh.bak pkg/mirthsync.bat.bak
	lein do clean, test, uberjar
	mkdir -p target/mirthsync-$(VERSION)/lib
	cp -a pkg target/mirthsync-$(VERSION)/bin
	cp target/uberjar/mirthsync-$(VERSION)-standalone.jar target/mirthsync-$(VERSION)/lib
	tar -C target/ -cvzf target/mirthsync-$(VERSION).tar.gz mirthsync-$(VERSION)
	cd target && zip -r mirthsync-$(VERSION).zip mirthsync-$(VERSION)
# Sign the release archives with the release key explicitly. The keyring may
# hold more than one secret key, so -u avoids gpg's default-key selection
# (matches the :signing :gpg-key in project.clj and the signed git tag).
	gpg -u jesse.dowell@gmail.com --detach-sign --armor target/mirthsync-$(VERSION).tar.gz
	gpg -u jesse.dowell@gmail.com --detach-sign --armor target/mirthsync-$(VERSION).zip
	cd target && sha256sum mirthsync-$(VERSION).tar.gz mirthsync-$(VERSION).zip > CHECKSUMS.txt
	lein do vcs assert-committed, vcs tag

.PHONY: release default build npm-prepare npm-pack npm-test-install npm-publish npm-clean
