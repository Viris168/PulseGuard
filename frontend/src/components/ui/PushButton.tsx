import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router-dom'
import { cn } from '../../lib/format'

// 3D push button; the styles live in index.css under .pushable.
function Layers({ children }: { children: ReactNode }) {
  return (
    <>
      <span className="pushable-shadow" aria-hidden />
      <span className="pushable-edge" aria-hidden />
      <span className="pushable-front">{children}</span>
    </>
  )
}

export function PushButton({ className, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button className={cn('pushable', className)} {...rest}>
      <Layers>{children}</Layers>
    </button>
  )
}

export function PushButtonLink({ className, children, ...rest }: LinkProps) {
  return (
    <Link className={cn('pushable', className)} {...rest}>
      <Layers>{children}</Layers>
    </Link>
  )
}
