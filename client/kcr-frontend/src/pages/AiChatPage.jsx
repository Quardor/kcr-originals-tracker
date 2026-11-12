import { useCallback, useEffect, useRef, useState } from 'react';
import { openChatSocket } from '../api/ai';
import { ErrorBox, Spinner } from '../components/ui';
import { aiToolLabel } from '../labels';

/**
 * UC-1: чат с AI-помощником (WebSocket /api/v1/ai/chat, протокол §7 ARCHITECTURE.md
 * kcr-ml). История — в памяти соединения: переподключение начинает новую сессию.
 * Ответы стримятся порциями (assistant_delta), выборки данных приходят отдельным
 * кадром data и рендерятся таблицей — числа всегда из источника, не из текста LLM.
 */

const EXAMPLES = [
  'Покажи клиентов, которым пора позвонить повторно',
  'У кого количество непереданных документов не меняется несколько срезов подряд?',
  'Покажи клиентов, которым сегодня требуется внимание',
];

let nextMessageId = 1;

export default function AiChatPage() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState(null);
  const [connecting, setConnecting] = useState(true);
  const socketRef = useRef(null);
  const assistantIdRef = useRef(null);
  const logRef = useRef(null);

  const scrollDown = useCallback(() => {
    requestAnimationFrame(() => {
      const log = logRef.current;
      if (log) log.scrollTop = log.scrollHeight;
    });
  }, []);

  const ensureAssistant = useCallback(() => {
    if (assistantIdRef.current == null) {
      const id = nextMessageId++;
      assistantIdRef.current = id;
      setMessages((prev) => [...prev, { id, role: 'assistant', text: '', table: null, status: null }]);
    }
    return assistantIdRef.current;
  }, []);

  const patchAssistant = useCallback((patch) => {
    const id = ensureAssistant();
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, ...patch } : m)));
  }, [ensureAssistant]);

  const appendDelta = useCallback((chunk) => {
    const id = ensureAssistant();
    setMessages((prev) => prev.map((m) => (m.id === id ? { ...m, text: m.text + chunk, status: null } : m)));
    scrollDown();
  }, [ensureAssistant, scrollDown]);

  const connect = useCallback(() => {
    setError(null);
    setConnecting(true);
    const socket = openChatSocket();
    socketRef.current = socket;

    socket.onopen = () => {
      setConnected(true);
      setConnecting(false);
    };
    socket.onmessage = (event) => {
      let frame;
      try {
        frame = JSON.parse(event.data);
      } catch {
        return;
      }
      switch (frame.type) {
        case 'assistant_delta':
          setStreaming(true);
          appendDelta(frame.content);
          break;
        case 'tool_status':
          patchAssistant({ status: `Запрашиваю данные: ${aiToolLabel(frame.tool)}…` });
          scrollDown();
          break;
        case 'data':
          patchAssistant({ table: { title: frame.title, columns: frame.columns, rows: frame.rows } });
          scrollDown();
          break;
        case 'assistant_done':
          setStreaming(false);
          assistantIdRef.current = null;
          break;
        case 'error':
          setStreaming(false);
          assistantIdRef.current = null;
          setError(`${frame.message} (${frame.code})`);
          break;
        default:
          break;
      }
    };
    socket.onclose = (event) => {
      setConnected(false);
      setStreaming(false);
      assistantIdRef.current = null;
      setConnecting(false);
      if (event.code === 4401) {
        setError('Сессия истекла: войдите заново и вернитесь в чат.');
      } else if (event.code !== 1000 && messages.length === 0) {
        setError('Соединение с AI-сервисом закрыто.');
      }
    };
    return socket;
  }, [appendDelta, messages.length, patchAssistant, scrollDown]);

  useEffect(() => {
    const socket = connect();
    return () => {
      socket.onclose = null; // размонтирование — не показываем ошибку закрытия
      socket.close(1000);
    };
    // connect зависит от messages.length только для текста ошибки; переподключение вручную
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const send = (text) => {
    const content = text.trim();
    const socket = socketRef.current;
    if (!content || !socket || socket.readyState !== WebSocket.OPEN || streaming) return;
    setError(null);
    assistantIdRef.current = null;
    setMessages((prev) => [...prev, { id: nextMessageId++, role: 'user', text: content }]);
    setInput('');
    socket.send(JSON.stringify({ type: 'user_message', content }));
    scrollDown();
  };

  const reconnect = () => {
    const socket = socketRef.current;
    if (socket && socket.readyState !== WebSocket.CLOSED) socket.close(1000);
    setMessages([]); // новая сессия = новая история
    connect();
  };

  const onKeyDown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send(input);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>AI-помощник</h1>
          <p className="page-hint">
            Вопросы по данным сервиса: клиенты, динамика передачи документов, история контактов.
            Помощник только читает данные — изменения вносит сотрудник.
          </p>
        </div>
        <div className="ai-chat-state">
          {connecting ? <Spinner label="Подключение…" /> : (
            <span className={`badge ${connected ? 'badge-on' : 'badge-off'}`}>
              {connected ? 'на связи' : 'нет соединения'}
            </span>
          )}
          {!connected && !connecting && (
            <button type="button" className="btn btn-ghost" onClick={reconnect}>Переподключиться</button>
          )}
        </div>
      </div>

      <ErrorBox message={error} />

      <div className="card ai-chat-card">
        <div className="ai-chat-log" ref={logRef}>
          {messages.length === 0 && (
            <div className="ai-chat-empty">
              <p>Задайте вопрос по данным — например:</p>
              <div className="chips">
                {EXAMPLES.map((example) => (
                  <button key={example} type="button" className="chip" onClick={() => send(example)}>
                    {example}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((message) => (
            <div key={message.id} className={`ai-msg ai-msg-${message.role}`}>
              <div className="ai-msg-author">{message.role === 'user' ? 'Вы' : 'AI-помощник'}</div>
              {message.status && <div className="ai-msg-status">{message.status}</div>}
              {message.text && <div className="ai-msg-text">{message.text}</div>}
              {message.table && <ChatTable table={message.table} />}
            </div>
          ))}
          {streaming && <div className="ai-typing">помощник печатает…</div>}
        </div>
        <div className="ai-chat-input">
          <textarea
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder={connected ? 'Спросите о клиентах, долгах, контактах…' : 'Нет соединения — переподключитесь'}
            rows={2}
            disabled={!connected || streaming}
          />
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => send(input)}
            disabled={!connected || streaming || !input.trim()}
          >
            Отправить
          </button>
        </div>
      </div>
    </div>
  );
}

function ChatTable({ table }) {
  return (
    <div className="table-wrap ai-chat-table">
      <div className="ai-table-title">{table.title}</div>
      <table className="table">
        <thead>
          <tr>{table.columns.map((column) => <th key={column}>{column}</th>)}</tr>
        </thead>
        <tbody>
          {table.rows.map((row, index) => (
            <tr key={index}>
              {row.map((cell, cellIndex) => <td key={cellIndex}>{cell ?? '—'}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
