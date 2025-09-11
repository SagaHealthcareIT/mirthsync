VERSION := 3.2.0-SNAPSHOT

default:
	echo "use lein directly or read the README.md file"

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
	gpg --detach-sign --armor target/mirthsync-$(VERSION).tar.gz
	gpg --detach-sign --armor target/mirthsync-$(VERSION).zip
	cd target && sha256sum mirthsync-$(VERSION).tar.gz mirthsync-$(VERSION).zip > CHECKSUMS.txt
	lein do vcs assert-committed, vcs tag

.PHONY: release default
