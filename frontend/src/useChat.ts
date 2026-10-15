import { useCallback, useRef, useState } from 'react'
import { streamChat, type Citation, type HistoryTurn } from './api'

/** How many earlier exchanges to send; the server decides how many it actually uses (app.chat.history-turns). */
const MAX_HISTORY = 10

export interface UserTurn {
  id: number
  role: 'user'
  text: string
}

export interface AssistantTurn {
  id: number
  role: 'assistant'
  text: string
  /** Chunks the model was given, numbered as it cites them. */
  sources: Citation[]
  /** Source indexes the finished answer cites; null while still streaming. */
  cited: number[] | null
  status: 'streaming' | 'done' | 'error' | 'stopped'
  /** The answer says the documents don't contain what was asked. */
  notFound?: boolean
  error?: string
  durationMs?: number
}

export type Turn = UserTurn | AssistantTurn

let nextId = 1

/** Conversation state plus the streaming request lifecycle (send, stop, errors). */
export function useChat() {
  const [turns, setTurns] = useState<Turn[]>([])
  const [streaming, setStreaming] = useState(false)
  const controller = useRef<AbortController | null>(null)

  const update = useCallback((id: number, change: (turn: AssistantTurn) => AssistantTurn) => {
    setTurns((all) => all.map((turn) => (turn.id === id && turn.role === 'assistant' ? change(turn) : turn)))
  }, [])

  const ask = useCallback(
    async (question: string) => {
      const text = question.trim()
      if (!text || controller.current) return
      const history = completedExchanges(turns).slice(-MAX_HISTORY)
      const id = nextId++
      setTurns((all) => [
        ...all,
        { id: nextId++, role: 'user', text },
        { id, role: 'assistant', text: '', sources: [], cited: null, status: 'streaming' },
      ])
      const abort = new AbortController()
      controller.current = abort
      setStreaming(true)
      try {
        for await (const event of streamChat(text, history, abort.signal)) {
          switch (event.type) {
            case 'sources':
              update(id, (turn) => ({ ...turn, sources: event.sources }))
              break
            case 'token':
              update(id, (turn) => ({ ...turn, text: turn.text + event.text }))
              break
            case 'done':
              update(id, (turn) => ({
                ...turn,
                status: 'done',
                cited: event.citedSources,
                notFound: event.notFound,
                durationMs: event.durationMs,
              }))
              break
            case 'error':
              update(id, (turn) => ({ ...turn, status: 'error', error: event.message }))
              break
          }
        }
        update(id, (turn) =>
          turn.status === 'streaming'
            ? { ...turn, status: 'error', error: 'The connection closed before the answer was finished.' }
            : turn,
        )
      } catch (error) {
        if (abort.signal.aborted) {
          update(id, (turn) => ({ ...turn, status: 'stopped' }))
        } else {
          const message = error instanceof Error ? error.message : String(error)
          update(id, (turn) => ({ ...turn, status: 'error', error: message }))
        }
      } finally {
        controller.current = null
        setStreaming(false)
      }
    },
    [turns, update],
  )

  const stop = useCallback(() => controller.current?.abort(), [])

  const clear = useCallback(() => {
    if (!controller.current) setTurns([])
  }, [])

  return { turns, streaming, ask, stop, clear }
}

/** Question-and-answer pairs whose answer finished normally; stopped or failed answers aren't useful context. */
export function completedExchanges(turns: Turn[]): HistoryTurn[] {
  const exchanges: HistoryTurn[] = []
  for (let i = 1; i < turns.length; i++) {
    const question = turns[i - 1]
    const answer = turns[i]
    if (question.role === 'user' && answer.role === 'assistant' && answer.status === 'done') {
      exchanges.push({ question: question.text, answer: answer.text })
    }
  }
  return exchanges
}
