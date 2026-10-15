import { useCallback, useRef, useState } from 'react'
import { streamChat, type Citation } from './api'

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
        for await (const event of streamChat(text, abort.signal)) {
          switch (event.type) {
            case 'sources':
              update(id, (turn) => ({ ...turn, sources: event.sources }))
              break
            case 'token':
              update(id, (turn) => ({ ...turn, text: turn.text + event.text }))
              break
            case 'done':
              update(id, (turn) => ({ ...turn, status: 'done', cited: event.citedSources, durationMs: event.durationMs }))
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
    [update],
  )

  const stop = useCallback(() => controller.current?.abort(), [])

  const clear = useCallback(() => {
    if (!controller.current) setTurns([])
  }, [])

  return { turns, streaming, ask, stop, clear }
}
