"use client";

import { useMyCards } from "@/api/me";
import { CardTile } from "@/components/banking";
import { Async, Card, Empty, PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Cards() {
  const t = useT();
  const cardsQ = useMyCards();
  return (
    <>
      <PageHeader title={t("nav.cards")} subtitle={t("cards.subtitle")} />
      <Card>
        <Async q={cardsQ} empty={<Empty title={t("cards.none")} />}>
          {(list) => <div className="grid gap-2 sm:grid-cols-2">{list.map((c) => <CardTile key={c.id} card={c} />)}</div>}
        </Async>
      </Card>
    </>
  );
}
