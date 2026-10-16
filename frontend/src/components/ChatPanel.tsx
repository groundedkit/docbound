import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import type { Citation, DocumentRecord } from '../api'
import { documentTitle } from '../format'
import { useChat } from '../useChat'
import { AssistantMessage } from './AssistantMessage'

interface Props {
  /** The uploaded documents, or null while they're still loading. */
  documents: DocumentRecord[] | null
  /** Set while the backend can't be reached; shown as a banner above the conversation. */
  backendError: string | null
  selected: Citation | null
  onOpenSource: (citation: Citation) => void
}

/** Starter questions for an empty conversation, phrased so they work for any document. */
function suggestions(titles: string[]): string[] {
  if (titles.length === 0) return []
  const first = titles[0]
  return [
    `What is the ${first} document about?`,
    `What are the most important points in ${first}?`,
    titles.length > 1 ? `How do ${first} and ${titles[1]} differ?` : `Summarize ${first} in a few bullet points.`,
  ]
}

export function ChatPanel({ documents, backendError, selected, onOpenSource }: Props) {
  const { turns, streaming, ask, stop, clear } = useChat()
  const [question, setQuestion] = useState('')
  const endRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const lastText = turns.at(-1)?.text
  const titles = (documents ?? []).map((document) => documentTitle(document.filename))

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

  function newChat() {
    clear()
    inputRef.current?.focus()
  }

  return (
    <section className="chat" aria-label="Chat">
      <header className="chat-header">
        <div>
          <h1>Ask your documents</h1>
          {documents && documents.length > 0 && (
            <p className="chat-subtitle">
              Answers come only from {documents.length === 1 ? titles[0] : `your ${documents.length} documents`}
            </p>
          )}
        </div>
        {turns.length > 0 && (
          <button type="button" className="button secondary small" onClick={newChat} disabled={streaming}>
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
        <div className="thread">
          {turns.length === 0 && (
            <div className="empty-state">
              {documents?.length === 0 ? (
                <>
                  <h2>Start with a document</h2>
                  <p>Upload a PDF, Word, Markdown or text file on the left, then ask a question about it.</p>
                </>
              ) : (
                <>
                  <h2>What would you like to know?</h2>
                  <p>Every answer cites the exact passages it comes from. Click a number to read the source.</p>
                  {titles.length > 0 && (
                    <ul className="suggestions" aria-label="Suggested questions">
                      {suggestions(titles).map((suggestion) => (
                        <li key={suggestion}>
                          <button type="button" onClick={() => void ask(suggestion)} disabled={streaming}>
                            {suggestion}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </>
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
      </div>

      <form className="composer" onSubmit={submit}>
        <div className="composer-box">
          <textarea
            ref={inputRef}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder={titles.length ? `Ask about ${titles.length === 1 ? titles[0] : 'your documents'}…` : 'Ask a question…'}
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
        </div>
        <p className="composer-hint">
          <kbd>Enter</kbd> to send · <kbd>Shift</kbd> + <kbd>Enter</kbd> for a new line
        </p>
      </form>
    </section>
  )
}
