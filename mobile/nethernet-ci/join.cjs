// CI-only player. A real Mojang BDS is downloaded by the test installer; this
// offline client is used only in a private test network. LAN discovery alone
// is not enough: require a WebRTC connection and a player-spawn packet.
const bedrock = require('bedrock-protocol')
const { Client: DiscoveryClient } = require('nethernet')
const { NethernetServerAdvertisement } = require('bedrock-protocol/src/nethernet/advertisement')
const { Versions, CURRENT_VERSION } = require('bedrock-protocol/src/options')

const host = process.argv[2] || '127.0.0.1'
const expectedName = process.argv[3] || 'Bloco CI'
let discovery
let client
let finished = false
let connected = false
let login = false
let spawn = false

function done (error) {
  if (finished) return
  finished = true
  clearTimeout(timer)
  if (discovery) discovery.close()
  if (client) client.close()
  if (error) console.error('FAIL: offline NetherNet player:', error.message || error)
  else console.log('PASS: descoberta LAN, ICE/UDP, login offline e spawn do cliente de teste')
  // The WebRTC stack has asynchronous close timers; do not leave a CI child.
  setTimeout(() => process.exit(error ? 1 : 0), 300)
}

const timer = setTimeout(() => done(new Error(`timeout: transport=${connected}, login=${login}, spawn=${spawn}`)), 90000)
discovery = new DiscoveryClient(0n, host)
discovery.on('error', done)
discovery.on('pong', packet => {
  if (client || finished) return
  let ad
  try { ad = NethernetServerAdvertisement.fromBuffer(Buffer.from(packet.data, 'hex')) } catch (_) { return }
  if (ad.motd !== expectedName) return
  const networkId = BigInt(packet.sender_id)
  const version = ad.version === 4 ? CURRENT_VERSION : Object.keys(Versions).find(key => Versions[key] === Number(ad.protocol))
  if (!version) return done(new Error('Unsupported advertised Bedrock protocol ' + ad.protocol))
  console.log(`LAN discovery: ${ad.motd}, protocol version ${version}`)
  discovery.close()
  discovery = null
  client = bedrock.createClient({
    host, port: 19132, version, username: 'BlocoCI', offline: true,
    transport: 'nethernet', nethernet: { networkId }, skipPing: true,
    connectTimeout: 60000, conLog: (...message) => console.log(...message)
  })
  client.on('error', done)
  if (!client.connection || !client.connection.nethernet) return done(new Error('NetherNet client was not initialized'))
  client.connection.nethernet.on('connected', () => { connected = true; console.log('NetherNet transport connected') })
  client.on('play_status', packet => {
    if (packet.status === 'login_success') login = true
    console.log('Bedrock play status:', packet.status)
  })
  client.on('spawn', () => {
    spawn = true
    if (!connected || !login) return done(new Error('Spawn occurred without an observed transport connection and offline login'))
    // Let the host log the entry before closing the client.
    setTimeout(() => done(), 1000)
  })
  client.on('disconnect', packet => done(new Error('Disconnected: ' + JSON.stringify(packet))))
  client.on('close', () => { if (!spawn) done(new Error('Client closed before spawn')) })
})
