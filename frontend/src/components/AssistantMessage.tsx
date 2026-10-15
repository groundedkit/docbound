import { NOT_FOUND, type Citation } from '../api'
import { splitCitations, stripCitations } from '../citations'
import type { AssistantTurn } from '../useChat'

interface Props {
  turn: AssistantTurn
  selected: Citation | null
  onOpenSource: (citation: Citation) => void
}

export function AssistantMessage({ turn, selected, onOpenSource }: Props) {
  const notFound = turn.status === 'done' && turn.cited?.length === 0 && turn.text.trim().startsWith(NOT_FOUND)
  // While streaming, any retrieved source can be cited; once done, only the ones the answer really used.
  const valid = new Set(turn.cited ?? turn.sources.map((source) => source.index))
  const byIndex = new Map(turn.sources.map((source) => [source.index, source]))
  const citedSources = (turn.cited ?? []).flatMap((index) => byIndex.get(index) ?? [])

  return (
    <div className={`message assistant${notFound ? ' not-found' : ''}`}>
      <div className="message-body">
        {turn.text === '' && turn.status === 'streaming' && (
          <span className="thinking">{turn.sources.length ? 'Writing an answer' : 'Searching your documents'}</span>
        )}
        {notFound
          ? stripCitations(turn.text)
          : splitCitations(turn.text, valid).map((part, i) => {
              if (part.kind === 'text') return <span key={i}>{part.text}</span>
              const source = byIndex.get(part.index)!
              return (
                <button
                  key={i}
                  type="button"
                  className={`cite${selected === source ? ' active' : ''}`}
                  onClick={() => onOpenSource(source)}
                  aria-label={`Source ${part.index}: ${source.filename}`}
                  title={source.filename}
                >
                  {part.index}
                </button>
              )
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
              >
                <span className="chip-index">{source.index}</span>
                <span className="chip-name">{source.filename}</span>
                <span className="chip-part">part {source.chunkIndex + 1}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {turn.status === 'error' && <p className="message-error" role="alert">{turn.error}</p>}
      {turn.status === 'stopped' && <p className="message-note">Stopped.</p>}
      {turn.status === 'done' && turn.durationMs !== undefined && turn.durationMs > 0 && (
        <p className="message-note">{(turn.durationMs / 1000).toFixed(1)} s</p>
      )}
    </div>
  )
}
