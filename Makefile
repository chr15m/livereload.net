build: $(shell find src) public/* public/webreload-template.zip
	mkdir -p build
	npx shadow-cljs release --debug app worker
	rsync -aLz --exclude js --exclude files --exclude '.*.swp' public/ build
	touch build

public/webreload-template.zip: src/webreload-template/**
	cd src && zip -r ../$@ webreload-template

node_modules: package.json public/webreload-template.zip
	pnpm i --no-lockfile --shamefully-hoist
	touch node_modules

.PHONY: watch clean

watch: node_modules
	npx shadow-cljs watch app worker

repl: node_modules
	npx shadow-cljs cljs-repl app

clean:
	rm -rf build

