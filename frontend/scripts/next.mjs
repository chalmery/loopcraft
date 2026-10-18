import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'

const require = createRequire(import.meta.url)
const nextPackage = require.resolve('next/package.json')
const nextCli = join(dirname(nextPackage), 'dist', 'bin', 'next')
const command = process.argv[2] ?? 'dev'
const args = [nextCli, command]

if (command === 'dev' || command === 'start') {
  args.push('--hostname', '127.0.0.1', '--port', process.env.FRONTEND_PORT || '5173')
}
if (command === 'dev' || command === 'build') args.push('--webpack')

const child = spawn(process.execPath, args, { stdio: 'inherit', env: process.env })
child.on('error', (error) => {
  process.stderr.write(`Could not start Next.js: ${error.message}\n`)
  process.exitCode = 1
})
child.on('exit', (code, signal) => {
  if (signal) process.kill(process.pid, signal)
  else process.exitCode = code ?? 1
})
