import { useState } from 'react'
import { cn } from '../../lib/format'

const LIGHT = 'radial-gradient(circle at 35% 30%, #ecfdf5, #6ee7b7 55%, #10b981)'
const DEEP = 'radial-gradient(circle at 35% 30%, #d1fae5, #34d399 45%, #047857)'

interface Props {
  /** Looks around and beats faster while an answer is on its way. */
  thinking?: boolean
  className?: string
}

/**
 * Ask AI's mascot: three soft orbs that float, blink and keep a heartbeat. Hover (or tap) for a hop.
 * Every animation is motion-safe, so it holds still for people who've asked for reduced motion.
 */
export function PulseMascot({ thinking = false, className }: Props) {
  // Bumping the key restarts the hop animation on every click.
  const [hops, setHops] = useState(0)

  const orb = (size: string, pos: string, bg: string, float: string) => (
    // Position on the wrapper, animate the inner element, so the two transforms don't fight.
    <span className={cn('absolute', size, pos)}>
      <span
        className={cn('block size-full rounded-full shadow-[0_18px_30px_-12px_rgb(4_120_87/0.45)]', float)}
        style={{ background: bg }}
      />
    </span>
  )

  return (
    <div
      className={cn('group relative mx-auto h-32 w-44 cursor-pointer select-none', className)}
      onClick={() => setHops((h) => h + 1)}
      aria-hidden
    >
      {/* Ground shadow */}
      <span className="absolute bottom-0 left-1/2 h-3 w-28 -translate-x-1/2">
        <span className="block size-full rounded-[50%] bg-emerald-900/20 blur-[3px] motion-safe:animate-pg-shadow dark:bg-black/50" />
      </span>

      <div key={hops} className={cn('absolute inset-x-0 top-0 h-28', hops > 0 && 'motion-safe:animate-pg-hop', 'group-hover:motion-safe:animate-pg-hop')}>
        {orb('size-14', 'left-2 top-9', LIGHT, 'motion-safe:animate-pg-float-slow [animation-delay:-1.1s]')}
        {orb('size-16', 'right-2 top-7', DEEP, 'motion-safe:animate-pg-float-slow [animation-delay:-2.3s]')}

        {/* The face orb */}
        <span className="absolute top-0 left-1/2 size-24 -translate-x-1/2">
          <span className="relative block size-full motion-safe:animate-pg-float">
            <span className="block size-full rounded-full shadow-[0_18px_30px_-12px_rgb(4_120_87/0.45)]" style={{ background: LIGHT }} />

            {/* Eyes: blink when idle, glance side to side while thinking. */}
            <span className={cn('absolute top-[30%] left-1/2 flex -translate-x-1/2 gap-3.5', thinking && 'motion-safe:animate-pg-look')}>
              {[0, 1].map((i) => (
                <span key={i} className="block h-3 w-2 rounded-full bg-emerald-950/85 motion-safe:animate-pg-blink" />
              ))}
            </span>

            {/* Heartbeat trace */}
            <svg viewBox="0 0 32 16" className="absolute top-[52%] left-1/2 h-5 w-10 -translate-x-1/2 overflow-visible">
              <path
                d="M1 9h6l3-6 5 11 3-7 2 2h11"
                fill="none"
                stroke="#065f46"
                strokeWidth="2.2"
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeDasharray="36"
                className={thinking ? 'motion-safe:animate-pg-beat-fast' : 'motion-safe:animate-pg-beat'}
              />
            </svg>
          </span>
        </span>
      </div>
    </div>
  )
}
