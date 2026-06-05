import { defineConfig } from "vite";

export default defineConfig({
  base: "./",
  plugins: [
    {
      name: "agentyard-en-dev-redirect",
      apply: "serve",
      configureServer(server) {
        server.middlewares.use((req, res, next) => {
          const url = req.url || "";
          const match = url.match(/^\/en(?:\/)?(\?.*)?$/);
          if (match) {
            const existingQuery = match[1] ? match[1].slice(1) : "";
            const params = new URLSearchParams(existingQuery);
            params.set("lang", "en");
            res.statusCode = 302;
            res.setHeader("Location", "/?" + params.toString());
            return res.end();
          }
          next();
        });
      }
    }
  ],
  build: {
    outDir: "dist",
    emptyOutDir: true
  }
});
