/**
 * SIGHTLINE Command Center — Server
 *
 * Receives WebSocket telemetry from the phone app and rebroadcasts
 * to any connected dashboard clients.
 *
 * Run: node server.js
 * Dashboard connects to http://localhost:3001
 * Phone sends telemetry to ws://<this-ip>:3001/telemetry
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const PORT = 3001;
const app = express();
const server = http.createServer(app);

app.use(cors());
app.use(express.json());

// Serve the built React dashboard
app.use(express.static('client/dist'));

const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST'],
  },
});

// Store latest state
let latestState = {
  goal: 'IDLE',
  target: '',
  confidence: 0,
  pathStatus: 'UNKNOWN',
  objects: [],
  lastResponse: '',
  timestamp: 0,
  connected: false,
};

// Phone connects here to send telemetry
const phoneNamespace = io.of('/telemetry');
phoneNamespace.on('connection', (socket) => {
  console.log(`[Phone] Connected: ${socket.id}`);
  latestState.connected = true;
  phoneNamespace.emit('state', latestState);

  socket.on('telemetry', (data) => {
    latestState = { ...data, connected: true };
    // Broadcast to all dashboard clients
    io.emit('state', latestState);
  });

  socket.on('disconnect', () => {
    console.log(`[Phone] Disconnected: ${socket.id}`);
    latestState.connected = false;
    io.emit('state', latestState);
  });
});

// Dashboard clients connect here
io.on('connection', (socket) => {
  console.log(`[Dashboard] Connected: ${socket.id}`);
  socket.emit('state', latestState);

  socket.on('disconnect', () => {
    console.log(`[Dashboard] Disconnected: ${socket.id}`);
  });
});

// Health check
app.get('/api/status', (req, res) => {
  res.json({
    status: 'running',
    phoneConnected: latestState.connected,
    lastUpdate: latestState.timestamp,
  });
});

// Serve React app for any other route
app.get('*', (req, res) => {
  res.sendFile(__dirname + '/client/dist/index.html');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n🗺️  SIGHTLINE Command Center running on http://localhost:${PORT}`);
  console.log(`📡 Phone telemetry: ws://<your-ip>:${PORT}/telemetry`);
  console.log(`📊 Dashboard: http://localhost:${PORT}\n`);
});
