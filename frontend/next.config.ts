import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emit a self-contained server (.next/standalone/server.js) with only the
  // traced dependencies, so the Docker runtime image stays small.
  output: "standalone",
};

export default nextConfig;
