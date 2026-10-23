import { defineConfig } from "vite";

// Die fertige Seite wird in die App gepackt und vom Webserver auf dem Host-Handy ausgeliefert.
export default defineConfig({
  base: "./",
  build: {
    outDir: "../app/src/main/assets/web",
    emptyOutDir: true,
  },
});
