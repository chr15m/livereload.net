build: $(shell find src) public/*
	mkdir -p build
	npx shadow-cljs release --debug app worker
	rsync -aLz --exclude js --exclude '.*.swp' public/ build
	touch build

node_modules: package.json
	pnpm i --no-lockfile --shamefully-hoist
	touch node_modules

.PHONY: watch clean

watch: node_modules
	npx shadow-cljs watch app worker

repl: node_modules
	npx shadow-cljs cljs-repl app

clean:
	rm -rf build

