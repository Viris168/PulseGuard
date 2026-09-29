import { SearchX } from 'lucide-react'
import { PageHeader } from '../components/layout/PageHeader'
import { ButtonLink } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'

export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" />
      <Card>
        <EmptyState
          icon={SearchX}
          title="There's nothing at this address"
          description="The link may be mistyped, or the page was moved."
          action={<ButtonLink to="/monitors">Go to monitors</ButtonLink>}
        />
      </Card>
    </>
  )
}
