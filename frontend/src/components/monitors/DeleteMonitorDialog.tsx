import { useState } from 'react'
import { deleteMonitor } from '../../api/monitors'
import { MONITORS_CHANGED } from '../../lib/events'
import { Button } from '../ui/Button'
import { Modal } from '../ui/Modal'

interface Props {
  /** The monitor to delete; null keeps the dialog closed. */
  monitor: { id: number; name: string } | null
  onClose: () => void
  onDeleted: (id: number) => void
}

export function DeleteMonitorDialog({ monitor, onClose, onDeleted }: Props) {
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    if (!monitor) return
    setDeleting(true)
    setError(null)
    try {
      await deleteMonitor(monitor.id)
      window.dispatchEvent(new Event(MONITORS_CHANGED))
      onDeleted(monitor.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not delete monitor')
    } finally {
      setDeleting(false)
    }
  }

  function close() {
    if (deleting) return
    setError(null)
    onClose()
  }

  return (
    <Modal
      open={!!monitor}
      onClose={close}
      title="Delete monitor?"
      footer={
        <>
          <Button variant="secondary" onClick={close} disabled={deleting}>
            Cancel
          </Button>
          <Button variant="danger" onClick={confirm} loading={deleting}>
            Delete
          </Button>
        </>
      }
    >
      <strong className="font-medium text-zinc-900 dark:text-zinc-100">{monitor?.name}</strong> and all of its check
      history and incidents will be permanently removed.
      {error && (
        <p className="mt-3 text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      )}
    </Modal>
  )
}
