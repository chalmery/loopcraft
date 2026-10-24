import type { NextConfig } from 'next'
import { BACKEND_URL } from './src/lib/backend'

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: '/api/:path*', destination: `${BACKEND_URL}/api/:path*` }]
  },
}

export default nextConfig
