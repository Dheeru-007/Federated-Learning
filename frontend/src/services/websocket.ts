// eslint-disable-next-line @typescript-eslint/no-var-requires

import { Client, IMessage } from '@stomp/stompjs';
import type { RoundUpdateMessage } from '../types';
const SockJS = require('sockjs-client');

export type RoundUpdateHandler = (message: RoundUpdateMessage) => void;

const WS_URL = 'http://localhost:8080/ws';

export function connectToSession(
  sessionId: number,
  onRoundUpdate: RoundUpdateHandler,
  onStatusUpdate: RoundUpdateHandler
): () => void {
  const client = new Client({
    webSocketFactory: () => new SockJS(WS_URL),
    reconnectDelay: 5000,
    debug: () => {},
  });

  client.onConnect = () => {
    client.subscribe(`/topic/sessions/${sessionId}/rounds`, (message: IMessage) => {
      try {
        const payload = JSON.parse(message.body) as RoundUpdateMessage;
        onRoundUpdate(payload);
      } catch {
        // ignore
      }
    });

    client.subscribe(`/topic/sessions/${sessionId}/status`, (message: IMessage) => {
      try {
        const payload = JSON.parse(message.body) as RoundUpdateMessage;
        onStatusUpdate(payload);
      } catch {
        // ignore
      }
    });
  };

  client.activate();
  return () => { client.deactivate(); };
}