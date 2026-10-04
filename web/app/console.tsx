'use client';

import { useState } from 'react';
import { useIncidentFeed } from '@/lib/useIncidentFeed';
import type { Severity } from '@/lib/types';

const WHO = 'operator';

export default function Console() {
  const { incidents, viewers, state, lagged, raise, acknowledge, dismissLag } =
    useIncidentFeed();
  const [title, setTitle] = useState('');
  const [severity, setSeverity] = useState<Severity>('WARNING');

  return (
    <>
      <div className="bar">
        <span className={`dot ${state}`} />
        <span>
          {state === 'live' && 'Live'}
          {state === 'connecting' && 'Connecting...'}
          {state === 'reconnecting' && 'Reconnecting...'}
          {state === 'offline' && 'Offline'}
        </span>
        <span className="spacer">
          {viewers} {viewers === 1 ? 'operator' : 'operators'} watching
        </span>
      </div>

      {lagged !== null && (
        <div className="warnbar">
          <span>
            Fell behind and missed {lagged} {lagged === 1 ? 'event' : 'events'}. The board
            has been resynchronised.
          </span>
          <button className="ghost" onClick={dismissLag}>
            Dismiss
          </button>
        </div>
      )}

      <form
        className="panel"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!title.trim()) return;
          await raise(title.trim(), severity);
          setTitle('');
        }}
      >
        <div className="row">
          <div>
            <label htmlFor="title">Raise an incident</label>
            <input
              id="title"
              value={title}
              placeholder="e.g. Checkout latency above 2s"
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="sev">Severity</label>
            <select
              id="sev"
              value={severity}
              onChange={(e) => setSeverity(e.target.value as Severity)}
            >
              <option>INFO</option>
              <option>WARNING</option>
              <option>CRITICAL</option>
            </select>
          </div>
          <button type="submit" disabled={!title.trim() || state !== 'live'}>
            Raise
          </button>
        </div>
      </form>

      {incidents.length === 0 ? (
        <p className="empty">
          Nothing on the board. Raise one above, or open this page in a second
          window and watch it appear in both.
        </p>
      ) : (
        <ul className="feed">
          {incidents.map((incident) => (
            <li key={incident.id}>
              <span className={`sev ${incident.severity}`}>{incident.severity}</span>
              <span className="title">
                {incident.title}
                <br />
                <span className="meta">
                  {incident.source} - {new Date(incident.raisedAt).toLocaleTimeString()}
                </span>
              </span>
              {incident.acknowledgedBy ? (
                <span className="acked">acknowledged by {incident.acknowledgedBy}</span>
              ) : (
                <button className="ghost" onClick={() => acknowledge(incident.id, WHO)}>
                  Acknowledge
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
