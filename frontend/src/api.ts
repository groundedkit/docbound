import { parseSse } from './sse'

export interface DocumentRecord {
  id: string
  filename: string
  contentType: string | null
  sizeBytes: number
  chunkCount: number
  createdAt: string
}

export interface Citation {
  index: number
  documentId: string
  filename: string
  chunkIndex: number
  score: number
  text: string
}

/** Events streamed by POST /api/chat, in order: sources, tokens, then done or error. */
export type ChatEvent =
  | { type: 'sources'; sources: Citation[] }
  | { type: 'token'; text: string }
  | {
      type: 'done'
      citedSources: number[]
      promptTokens: number | null
      completionTokens: number | null
      durationMs: number
    }
  | { type: 'error'; message: string }

/** Must match ChatService.NOT_FOUND on the backend. */
export const NOT_FOUND = "I couldn't find that in your documents."

/** Shown when the backend can't be reached at all, e.g. it's still starting or Ollama isn't running. */
export const BACKEND_UNAVAILABLE =
  "Can't reach the backend. It may still be starting (the first start downloads the AI models), or Ollama isn't " +
  'running. Check with `docker compose logs backend`.'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function listDocuments(): Promise<DocumentRecord[]> {
  return json(await request('/api/documents'))
}

export async function uploadDocument(file: File): Promise<DocumentRecord> {
  const form = new FormData()
  form.append('file', file)
  return json(await request('/api/documents', { method: 'POST', body: form }))
}

export async function deleteDocument(id: string): Promise<void> {
  const response = await request(`/api/documents/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!response.ok) throw await toError(response)
}

export async function* streamChat(question: string, signal?: AbortSignal): AsyncGenerator<ChatEvent> {
  const response = await request('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ question }),
    signal,
  })
  if (!response.ok || !response.body) throw await toError(response)
  for await (const message of parseSse(response.body)) {
    yield { type: message.event, ...JSON.parse(message.data) } as ChatEvent
  }
}

/** fetch() that reports an unreachable server as an ApiError instead of a bare TypeError. */
async function request(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch (error) {
    if (init?.signal?.aborted) throw error
    throw new ApiError(0, BACKEND_UNAVAILABLE)
  }
}

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) throw await toError(response)
  return response.json() as Promise<T>
}

/**
 * Turns an error response into a readable message. The backend's own errors are RFC 9457 problem JSON; a 5xx
 * without one comes from the proxy in front of it (nginx or Vite), meaning the backend isn't reachable.
 */
export async function toError(response: Response): Promise<ApiError> {
  try {
    const problem = await response.json()
    if (typeof problem?.detail === 'string') return new ApiError(response.status, problem.detail)
  } catch {
    // Not JSON, e.g. a proxy error page.
  }
  if (response.status >= 500) return new ApiError(response.status, BACKEND_UNAVAILABLE)
  return new ApiError(response.status, `Request failed (${response.status})`)
}
