import Console from './console';

export default function Page() {
  return (
    <>
      <h1>Pulse</h1>
      <p className="lede">
        A live incident console. Events are pushed over a WebSocket to every
        connected operator, acknowledgements broadcast instantly, and a client
        that loses its connection resumes from where it left off instead of
        quietly missing what happened while it was away.
      </p>
      <Console />
    </>
  );
}
