import React from 'react';
import { SkeletonPlaceholder, SkeletonText, Tile } from '@carbon/react';

/**
 * The placeholders a plan tab shows while its first data comes in. Each one
 * mirrors the layout of its tab, so the page does not jump once the data
 * replaces it. They are decorative: the live region announcing the load is
 * the tab's own aria-busy wrapper.
 */

/** The plan header every tab opens with: title, meta line and the KPI tiles. */
function KopSkeleton({ kpis = 0 }: { kpis?: number }) {
  return (
    <>
      <div className="pdca-page-header">
        <SkeletonText heading width="60%" />
        <SkeletonText width="35%" />
      </div>
      {kpis > 0 && (
        <div className="pdca-kpi-grid">
          {Array.from({ length: kpis }, (_, i) => (
            <Tile key={i} className="pdca-kpi-card">
              <SkeletonText width="60%" />
              <SkeletonText heading width="40%" />
              <SkeletonText width="50%" />
            </Tile>
          ))}
        </div>
      )}
    </>
  );
}

/** A card with a header and a few lines, as the overview's cards look. */
function KaartSkeleton({ regels = 3 }: { regels?: number }) {
  return (
    <Tile className="pdca-card">
      <div className="pdca-card-header"><SkeletonText width="40%" /></div>
      <div className="pdca-card-body">
        {Array.from({ length: regels }, (_, i) => (
          <div key={i} className="pdca-skeleton-rij">
            <SkeletonPlaceholder className="pdca-skeleton-tegel" />
            <div className="pdca-skeleton-tekst">
              <SkeletonText width="70%" />
              <SkeletonText width="40%" />
            </div>
          </div>
        ))}
      </div>
    </Tile>
  );
}

/** Planoverzicht: header, KPI tiles and the two columns of cards. */
export function OverzichtSkeleton() {
  return (
    <div className="pdca-container" aria-busy="true" aria-label="Planoverzicht laden">
      <KopSkeleton kpis={4} />
      <div className="pdca-content-grid">
        <div><KaartSkeleton regels={4} /></div>
        <div><KaartSkeleton regels={2} /></div>
      </div>
    </div>
  );
}

/** Doelen: the hoofddoel card and a stack of collapsed subdoel rows. */
export function DoelenSkeleton({ rijen = 5 }: { rijen?: number }) {
  return (
    <div className="pdca-container" aria-busy="true" aria-label="Doelen laden">
      <KopSkeleton />
      <div className="pdca-goal-card pdca-goal-card--hoofddoel">
        <div className="pdca-goal-header"><SkeletonText width="55%" /></div>
      </div>
      {Array.from({ length: rijen }, (_, i) => (
        <div key={i} className="pdca-goal-card">
          <div className="pdca-goal-header"><SkeletonText width={`${65 - i * 5}%`} /></div>
        </div>
      ))}
    </div>
  );
}

/** Evaluaties: the header and a list of evaluation rows. */
export function EvaluatiesSkeleton({ rijen = 4 }: { rijen?: number }) {
  return (
    <div className="pdca-container" aria-busy="true" aria-label="Evaluaties laden">
      <KopSkeleton />
      <KaartSkeleton regels={rijen} />
    </div>
  );
}
