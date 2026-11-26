// Wires the bundled EPUBCheck engine (official W3C build, TeaVM-compiled) to the
// Android side. EpubCheckRunner serves these modules and the book itself from
// https://lexi.local/ so that ES module imports and fetch() work normally.

import { validate, configureEngine, EPUBCHECK_VERSION } from "./validate-browser.js";

configureEngine({ url: "./epubcheck-engine.js" });

function send(payload) {
    try {
        if (window.LexiCheck && window.LexiCheck.onResult) {
            window.LexiCheck.onResult(JSON.stringify(payload));
        }
    } catch (error) {
        // Nothing to do: the host is gone.
    }
}

window.lexiValidateEpub = async function (url) {
    try {
        const response = await fetch(url, { cache: "no-store" });
        if (!response.ok) throw new Error("fetch " + response.status);
        const bytes = new Uint8Array(await response.arrayBuffer());
        const result = await validate(bytes);
        send({ ok: true, engine: EPUBCHECK_VERSION, result: result });
    } catch (error) {
        send({ ok: false, engine: EPUBCHECK_VERSION, error: String(error && error.message ? error.message : error) });
    }
};

try {
    if (window.LexiCheck && window.LexiCheck.onReady) {
        window.LexiCheck.onReady(EPUBCHECK_VERSION);
    }
} catch (error) {
    // Ignore: readiness is advisory.
}
