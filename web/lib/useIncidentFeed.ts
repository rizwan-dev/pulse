'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConnectionState, Incident, ServerMessage } from './types';

const HTTP = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';
const WS = HTTP.replace(/^http/, 'ws');

/** Capped exponential backoff. A tight reconnect loop is a self-inflicted DDoS. */
function backoffMs(attempt: number): number {
  return Math.min(30_000, 500 * 2 ** Math.min(attempt, 6));
}

export function useIncidentFeed() {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [viewers, setViewers] = useState(0);
  const [state, setState] = useState<ConnectionState>('connecting');
  const [lagged, setLagged] = useState<number | null>(null);

  // The last sequence number we have actually applied. This is what makes a
  // dropped connection survivable: we reconnect with ?since=<cursor> and the
  // server replays only the gap.
  const cursor = useRef(0);
  const attempt = useRef(0);
  const socket = useRef<WebSocket | null>(null);
  const closedByUs = useRef(false);

  const apply = useCallback((message: ServerMessage) => {
    switch (message.type) {
      case 'snapshot':
        setIncidents(message.incidents);
        cursor.current = message.seq;
        break;
      case 'raised':
        setIncidents((current) => [message.incident, ...current]);
        cursor.current = Math.max(cursor.current, message.incident.seq);
        break;
      case 'acknowledged':
        setIncidents((current) =>
          current.map((i) => (i.id === message.incident.id ? message.incident : i)),
        );
        cursor.current = Math.max(cursor.current, message.incident.seq);
        break;
      case 'presence':
        setViewers(message.viewers);
        break;
      case 'lagged':
        // The server could not keep us up to date. Say so rather than showing a
        // board that looks current and is not.
        setLagged(message.missed);
        cursor.current = message.resumeFrom;
        break;
    }
  }, []);

  useEffect(() => {
    closedByUs.current = false;
    let timer: ReturnType<typeof setTimeout>;

    const connect = () => {
      const since = cursor.current > 0 ? `?since=${cursor.current}` : '';
      const ws = new WebSocket(`${WS}/ws${since}`);
      socket.current = ws;

      ws.onopen = () => {
        attempt.current = 0;
        setState('live');
      };

      ws.onmessage = (event) => {
        try {
          apply(JSON.parse(event.data as string) as ServerMessage);
        } catch {
          // A frame we cannot parse is a contract mismatch, not a reason to
          // tear down a working connection.
        }
      };

      ws.onclose = () => {
        if (closedByUs.current) return;
        setState('reconnecting');
        const wait = backoffMs(attempt.current++);
        timer = setTimeout(connect, wait);
      };

      ws.onerror = () => ws.close();
    };

    connect();

    return () => {
      closedByUs.current = true;
      clearTimeout(timer);
      socket.current?.close();
    };
  }, [apply]);

  const raise = useCallback(async (title: string, severity: Incident['severity']) => {
    await fetch(`${HTTP}/api/incidents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, severity, source: 'console' }),
    });
    // No local state update: the change arrives over the socket like everyone
    // else's. One path in means the screen cannot disagree with the server.
  }, []);

  const acknowledge = useCallback(async (id: string, by: string) => {
    await fetch(`${HTTP}/api/incidents/${id}/ack`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ by }),
    });
  }, []);

  return { incidents, viewers, state, lagged, raise, acknowledge, dismissLag: () => setLagged(null) };
}
