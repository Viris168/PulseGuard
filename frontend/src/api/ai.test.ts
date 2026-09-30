import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import { createSseParser, streamMessage } from './ai'
import { setSession } from './session'
import type { AuthResponse } from '../types/auth'

describe('createSseParser', () => {
  function parse(chunks: string[]) {
    const events: [string, string][] = []
    const parser = createSseParser((event, data) => events.push([event, data]))
    chunks.forEach((c) => parser.push(c))
    parser.end()
    return events
  }

  it("reads Spring's format, with no space after the colon", () => {
    expect(parse(['event:delta\ndata:{"text":"Hi"}\n\n'])).toEqual([['delta', '{"text":"Hi"}']])
  })

  it('accepts a space after the colon, as the SSE format allows', () => {
    expect(parse(['event: done\ndata: {}\n\n'])).toEqual([['done', '{}']])
  })

  it('reassembles events split anywhere across network chunks', () => {
    expect(parse(['eve', 'nt:delta\nda', 'ta:{"text":"He', 'llo"}\n', '\nevent:done\ndata:{}\n\n'])).toEqual([
      ['delta', '{"text":"Hello"}'],
      ['done', '{}'],
    ])
  })

  it('handles several events in one chunk and \\r\\n line ends', () => {
    expect(parse(['event:delta\r\ndata:{"text":"a"}\r\n\r\nevent:delta\ndata:{"text":"b"}\n\n'])).toEqual([
      ['delta', '{"text":"a"}'],
      ['delta', '{"text":"b"}'],
    ])
  })

  it('ignores comments and keeps a last event that has no blank line after it', () => {
    expect(parse([':keep-alive\n\nevent:done\ndata:{"answerId":1}'])).toEqual([['done', '{"answerId":1}']])
  })
})

describe('streamMessage', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    setSession({ token: 'access-1', refreshToken: 'refresh-1', tokenType: 'Bearer', expiresIn: 900, user: {} } as AuthResponse)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setSession(null)
  })

  /** A 200 SSE response whose body arrives in exactly these chunks. */
  function sse(chunks: string[]) {
    const encoder = new TextEncoder()
    return new Response(
      new ReadableStream({
        start(controller) {
          chunks.forEach((c) => controller.enqueue(encoder.encode(c)))
          controller.close()
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    )
  }

  it('passes each piece to onDelta and returns the done event', async () => {
    fetchMock.mockResolvedValueOnce(
      sse([
        'event:delta\ndata:{"text":"Health is"}\n\n',
        'event:delta\ndata:{"text":" down."}\n\nevent:done\ndata:',
        '{"questionId":1,"answerId":2,"status":"COMPLETE","quota":{"used":1,"limit":5}}\n\n',
      ]),
    )
    const pieces: string[] = []

    const result = await streamMessage(42, '  Is Health down?  ', { onDelta: (t) => pieces.push(t) })

    expect(pieces).toEqual(['Health is', ' down.'])
    expect(result).toEqual({
      kind: 'done',
      done: { questionId: 1, answerId: 2, status: 'COMPLETE', quota: { used: 1, limit: 5 } },
    })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/ai/conversations/42/messages')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer access-1')
    expect(JSON.parse(init.body as string).question).toBe('Is Health down?')
  })

  it('returns the error event when the answer ends early', async () => {
    fetchMock.mockResolvedValueOnce(
      sse(['event:error\ndata:{"message":"Ask AI couldn\'t answer just now.","counted":false,"answerId":null}\n\n']),
    )

    const result = await streamMessage(42, 'Hi', { onDelta: () => {} })

    expect(result).toEqual({ kind: 'error', error: { message: "Ask AI couldn't answer just now.", counted: false, answerId: null } })
  })

  it('throws refusals before the stream as ApiError, like any other call', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ status: 429, message: "You've used all 5 questions for today on the Free plan." }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    await expect(streamMessage(42, 'Hi', { onDelta: () => {} })).rejects.toMatchObject({ status: 429 } satisfies Partial<ApiError>)
  })

  it('reports Stop as stopped, not as an error', async () => {
    const controller = new AbortController()
    fetchMock.mockImplementationOnce((_url: string, init: RequestInit) => {
      const encoder = new TextEncoder()
      const body = new ReadableStream({
        start(c) {
          c.enqueue(encoder.encode('event:delta\ndata:{"text":"It means"}\n\n'))
          init.signal?.addEventListener('abort', () => c.error(new DOMException('Aborted', 'AbortError')))
        },
      })
      return Promise.resolve(new Response(body, { status: 200 }))
    })
    const pieces: string[] = []

    const result = await streamMessage(42, 'Explain 503', {
      signal: controller.signal,
      onDelta: (t) => {
        pieces.push(t)
        controller.abort() // Stop after the first piece
      },
    })

    expect(pieces).toEqual(['It means'])
    expect(result).toEqual({ kind: 'stopped' })
  })
})
