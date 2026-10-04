// Same-origin Monaco worker bootstrap, shared by all editor languages.
self.MonacoEnvironment = {
    baseUrl: new URL("/min/", self.location.href).href
};
importScripts(new URL("vs/base/worker/workerMain.js", self.MonacoEnvironment.baseUrl).href);
