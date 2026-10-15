import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import type { Citation } from '../api'
import { useChat } from '../useChat'
import { AssistantMessage } from './AssistantMessage'

interface Props {
  documentCount: number | null
  /** Set while the backend can't be reached; shown as a banner above the conversation. */
  backendError: string | null
  selected: Citation | null
  onOpenSource: (citation: Citation) => void
}

export function ChatPanel({ documentCount, backendError, selected, onOpenSource }: Props) {
  const { turns, streaming, ask, stop, clear } = useChat()
  const [question, setQuestion] = useState('')
  const endRef = useRef<HTMLDivElement>(null)
  const lastText = turns.at(-1)?.text

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [turns.length, lastText])

  function submit(event?: FormEvent) {
    event?.preventDefault()
    if (!question.trim() || streaming) return
    void ask(question)
    setQuestion('')
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) submit(event)
  }

  return (
    <section className="chat" aria-label="Chat">
      <header className="chat-header">
        <h1>Ask your documents</h1>
        {turns.length > 0 && (
          <button type="button" className="link-button" onClick={clear} disabled={streaming}>
            New chat
          </button>
        )}
      </header>

      {backendError && (
        <div className="banner" role="alert">
          <p>{backendError}</p>
          <p className="banner-note">Retrying automatically…</p>
        </div>
      )}

      <div className="messages" aria-live="polite">
        {turns.length === 0 && (
          <div className="empty-state">
            {documentCount === 0 ? (
              <p>Upload a PDF, Word, Markdown or text file on the left, then ask a question about it.</p>
            ) : (
              <p>Ask a question. Answers come only from your documents and cite the passages they use.</p>
            )}
          </div>
        )}
        {turns.map((turn) =>
          turn.role === 'user' ? (
            <div key={turn.id} className="message user">
              <div className="message-body">{turn.text}</div>
            </div>
          ) : (
            <AssistantMessage key={turn.id} turn={turn} selected={selected} onOpenSource={onOpenSource} />
          ),
        )}
        <div ref={endRef} />
      </div>

      <form className="composer" onSubmit={submit}>
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Ask a question…"
          rows={1}
          maxLength={2000}
          aria-label="Question"
        />
        {streaming ? (
          <button type="button" className="button secondary" onClick={stop}>
            Stop
          </button>
        ) : (
          <button type="submit" className="button primary" disabled={!question.trim()}>
            Send
          </button>
        )}
      </form>
    </section>
  )
}
