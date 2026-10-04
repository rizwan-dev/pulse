export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export type Incident = {
  seq: number;
  id: string;
  title: string;
  severity: Severity;
  source: string;
  raisedAt: string;
  acknowledgedBy?: string | null;
};

/**
 * Mirrors the Kotlin sealed interface. The server serialises it with a 'type'
 * discriminator, so this union is exhaustive - a `switch` over `type` with no
 * default will fail to compile the day the server grows a new message, which is
 * exactly when you want to find out.
 */
export type ServerMessage =
  | { type: 'snapshot'; incidents: Incident[]; seq: number }
  | { type: 'raised'; incident: Incident }
  | { type: 'acknowledged'; incident: Incident }
  | { type: 'presence'; viewers: number }
  | { type: 'lagged'; missed: number; resumeFrom: number };

export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline';
