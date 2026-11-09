import { defineConfig } from 'vite';

// The dev/preview server only listens on this PC (localhost). Nothing else on the
// network - and nothing on the internet - can open it. Passing `--host` would
// expose it to the local network, so don't do that on an untrusted Wi-Fi.
export default defineConfig({
  server: { host: 'localhost', strictPort: true },
  preview: { host: 'localhost', strictPort: true },
});
