import { Construction } from 'lucide-react'
import { PageHeader } from '../components/layout/PageHeader'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'

/** Placeholder for pages scheduled in later parts of the frontend build. */
export function ComingSoonPage({ title, part }: { title: string; part: string }) {
  return (
    <>
      <PageHeader title={title} />
      <Card>
        <EmptyState icon={Construction} title="Coming soon" description={`This page is planned for ${part}.`} />
      </Card>
    </>
  )
}
