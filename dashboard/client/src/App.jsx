import { useState, useEffect, useRef } from 'react';
import { io } from 'socket.io-client';
import './App.css';

/**
 * SIGHTLINE Command Center Dashboard
 *
 * Live visualization of what the phone is tracking:
 * - Spatial map (objects plotted by position)
 * - Current goal + target
 * - Confidence meter
 * - Path status
 * - Connection status
 */

const GOAL_COLORS = {
  IDLE: '#666',
  FIND: '#2196F3',
  GUIDE: '#4CAF50',
  UNDERSTAND: '#9C27B0',
  REMEMBER: '#FF9800',
};

const DISTANCE_COLORS = {
  NEAR: '#ff4444',
  MID: '#ffaa00',
  FAR: '#44ff44',
};

export default function App() {
  const [state, setState] = useState({
    goal: 'IDLE',
    target: '',
    confidence: 0,
    pathStatus: 'UNKNOWN',
    objects: [],
    lastResponse: '',
    timestamp: 0,
    connected: false,
  });
  const [serverConnected, setServerConnected] = useState(false);
  const socketRef = useRef(null);

  useEffect(() => {
    const socket = io(window.location.origin, {
      reconnection: true,
      reconnectionDelay: 2000,
    });
    socketRef.current = socket;

    socket.on('connect', () => setServerConnected(true));
    socket.on('disconnect', () => setServerConnected(false));
    socket.on('state', (data) => setState(data));

    return () => socket.disconnect();
  }, []);

  return (
    <div className="dashboard">
      {/* Header */}
      <header className="header">
        <div className="header-left">
          <h1>SIGHTLINE</h1>
          <span className="subtitle">Command Center</span>
        </div>
        <div className="header-right">
          <div className={`status-dot ${serverConnected ? 'connected' : 'disconnected'}`} />
          <span>{serverConnected ? 'Connected' : 'Waiting for phone…'}</span>
          <div className={`status-dot ${state.connected ? 'connected' : 'disconnected'}`} />
          <span>{state.connected ? 'Phone Active' : 'Phone Offline'}</span>
        </div>
      </header>

      <div className="main">
        {/* Spatial Map */}
        <section className="panel spatial-map">
          <h2>Spatial Map</h2>
          <div className="map-container">
            {/* User position */}
            <div className="user-dot" title="You">
              <div className="user-ring" />
              <div className="user-center" />
            </div>

            {/* Direction labels */}
            <div className="direction-label left">← LEFT</div>
            <div className="direction-label center">CENTER</div>
            <div className="direction-label right">RIGHT →</div>

            {/* Distance zones */}
            <div className="zone-label near">NEAR</div>
            <div className="zone-label mid">MID</div>
            <div className="zone-label far">FAR</div>

            {/* Detected objects */}
            {state.objects.map((obj, i) => (
              <ObjectDot key={i} obj={obj} />
            ))}
          </div>
        </section>

        {/* Info Panels */}
        <div className="side-panels">
          {/* Current Goal */}
          <section className="panel goal-panel">
            <h2>Current Goal</h2>
            <div
              className="goal-badge"
              style={{ backgroundColor: GOAL_COLORS[state.goal] || '#666' }}
            >
              {state.goal}
            </div>
            {state.target && (
              <div className="target-text">
                Target: <strong>{state.target}</strong>
              </div>
            )}
          </section>

          {/* Confidence */}
          <section className="panel confidence-panel">
            <h2>Confidence</h2>
            <div className="confidence-bar">
              <div
                className="confidence-fill"
                style={{
                  width: `${(state.confidence * 100).toFixed(0)}%`,
                  backgroundColor:
                    state.confidence > 0.7 ? '#4CAF50' :
                    state.confidence > 0.4 ? '#FF9800' : '#f44336',
                }}
              />
            </div>
            <div className="confidence-value">
              {(state.confidence * 100).toFixed(0)}%
            </div>
          </section>

          {/* Path Status */}
          <section className="panel path-panel">
            <h2>Path Status</h2>
            <div className={`path-status ${state.pathStatus.toLowerCase()}`}>
              {state.pathStatus}
            </div>
          </section>

          {/* Last Response */}
          <section className="panel response-panel">
            <h2>Last Response</h2>
            <div className="response-text">
              {state.lastResponse || "—"}
            </div>
          </section>

          {/* Object Count */}
          <section className="panel objects-panel">
            <h2>Detected Objects</h2>
            <div className="object-count">{state.objects.length}</div>
            <div className="object-list">
              {state.objects.slice(0, 8).map((obj, i) => (
                <div key={i} className="object-item">
                  <span className="obj-label">{obj.label}</span>
                  <span className="obj-confidence">
                    {(obj.confidence * 100).toFixed(0)}%
                  </span>
                  <span
                    className="obj-zone"
                    style={{ color: DISTANCE_COLORS[obj.distance] || '#aaa' }}
                  >
                    {obj.distance}
                  </span>
                </div>
              ))}
            </div>
          </section>
        </div>
      </div>

      {/* Footer */}
      <footer className="footer">
        <span>SIGHTLINE Command Center — iQOO Hackathon</span>
        <span>{new Date(state.timestamp).toLocaleTimeString()}</span>
      </footer>
    </div>
  );
}

function ObjectDot({ obj }) {
  // Map normalised coordinates to map position
  const x = obj.x * 100; // percentage
  const y = (1 - obj.y) * 100; // invert Y

  return (
    <div
      className="object-dot"
      style={{
        left: `${x}%`,
        bottom: `${y}%`,
        backgroundColor: DISTANCE_COLORS[obj.distance] || '#aaa',
      }}
      title={`${obj.label} (${(obj.confidence * 100).toFixed(0)}%)`}
    >
      <span className="obj-dot-label">{obj.label}</span>
    </div>
  );
}
