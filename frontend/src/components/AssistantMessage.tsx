import { useState, type ReactNode } from 'react'
import type { Citation } from '../api'
import { splitCitations, stripCitations } from '../citations'
import { parseBlocks, splitBold } from '../format'
import type { AssistantTurn } from '../useChat'

interface Props {
  turn: AssistantTurn
  selected: Citation | null
  onOpenSource: (citation: Citation) => void
}

export function AssistantMessage({ turn, selected, onOpenSource }: Props) {
  const notFound = turn.status === 'done' && turn.notFound === true
  // While streaming, any retrieved source can be cited; once done, only the ones the answer really used.
  const valid = new Set(turn.cited ?? turn.sources.map((source) => source.index))
  const byIndex = new Map(turn.sources.map((source) => [source.index, source]))
  const citedSources = (turn.cited ?? []).flatMap((index) => byIndex.get(index) ?? [])
  const text = notFound ? stripCitations(turn.text) : turn.text
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(stripCitations(turn.text).trim())
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard access can be refused (e.g. an insecure origin); the button then just does nothing.
    }
  }

  /** One line of an answer: bold spans plus clickable [n] citations. */
  function inline(line: string): ReactNode[] {
    return splitBold(line).flatMap((part, p) =>
      splitCitations(part.text, notFound ? new Set<number>() : valid).map((piece, i) => {
        const key = `${p}-${i}`
        if (piece.kind === 'text') return part.bold ? <strong key={key}>{piece.text}</strong> : <span key={key}>{piece.text}</span>
        const source = byIndex.get(piece.index)!
        return (
          <button
            key={key}
            type="button"
            className={`cite${selected === source ? ' active' : ''}`}
            onClick={() => onOpenSource(source)}
            aria-label={`Source ${piece.index}: ${source.title}`}
            title={`${source.title} (${source.filename})`}
          >
            {piece.index}
          </button>
        )
      }),
    )
  }

  return (
    <div className={`message assistant${notFound ? ' not-found' : ''}`}>
      <div className="message-body">
        {turn.text === '' && turn.status === 'streaming' && (
          <span className="thinking">
            {turn.sources.length ? 'Writing an answer' : 'Searching your documents'}
            <span className="dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
          </span>
        )}
        {parseBlocks(text).map((block, b) => {
          if (block.kind === 'paragraph') {
            return (
              <p key={b}>
                {block.lines.map((line, l) => (
                  <span key={l}>
                    {l > 0 && <br />}
                    {inline(line)}
                  </span>
                ))}
              </p>
            )
          }
          const items = block.items.map((item, i) => <li key={i}>{inline(item)}</li>)
          return block.kind === 'bullets' ? <ul key={b}>{items}</ul> : <ol key={b}>{items}</ol>
        })}
        {turn.status === 'streaming' && turn.text !== '' && <span className="cursor" aria-hidden="true" />}
      </div>

      {citedSources.length > 0 && (
        <ul className="source-chips" aria-label="Sources">
          {citedSources.map((source) => (
            <li key={source.index}>
              <button
                type="button"
                className={`source-chip${selected === source ? ' active' : ''}`}
                onClick={() => onOpenSource(source)}
                title={source.filename}
              >
                <span className="chip-index">{source.index}</span>
                <span className="chip-name">{source.title}</span>
                <span className="chip-part">part {source.chunkIndex + 1}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {turn.status === 'error' && <p className="message-error" role="alert">{turn.error}</p>}
      {turn.status === 'stopped' && <p className="message-note">Stopped.</p>}
      {turn.status === 'done' && !notFound && (
        <div className="message-actions">
          <button type="button" className="text-button" onClick={() => void copy()}>
            {copied ? 'Copied' : 'Copy'}
          </button>
          {turn.durationMs !== undefined && turn.durationMs > 0 && (
            <span className="message-note">{(turn.durationMs / 1000).toFixed(1)} s</span>
          )}
        </div>
      )}
      {turn.status === 'done' && notFound && turn.durationMs !== undefined && turn.durationMs > 0 && (
        <p className="message-note">{(turn.durationMs / 1000).toFixed(1)} s</p>
      )}
    </div>
  )
}
