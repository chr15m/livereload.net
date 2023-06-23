build: $(shell find src) public/* public/livereload-template.zip package.json server.cljs deploy/*
	mkdir -p build
	npx shadow-cljs release --debug app worker
	rsync -aLz --exclude js --exclude files --exclude '.*.swp' public/ build
	cp package.json server.cljs deploy/* build/
	touch build

public/livereload-template.zip: src/livereload-template/**
	cd src && zip -r ../$@ livereload-template

node_modules: package.json public/livereload-template.zip
	pnpm i --no-lockfile --shamefully-hoist
	touch node_modules

src/fa: node_modules

.PHONY: watch clean

watch: node_modules
	npx shadow-cljs watch app worker

repl: node_modules
	npx shadow-cljs cljs-repl app

clean:
	rm -rf build

