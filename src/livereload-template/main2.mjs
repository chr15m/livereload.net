console.log("main2.mjs");

function timeout(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

await timeout(500);

console.log("main2.mjs done.");
