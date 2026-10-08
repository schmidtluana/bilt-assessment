const toneClasses = {
  success: "border-emerald-200 bg-emerald-50 text-emerald-800",
  neutral: "border-slate-200 bg-slate-50 text-slate-700",
  warning: "border-amber-200 bg-amber-50 text-amber-800",
};

const numberFormatter = new Intl.NumberFormat("en-US");

const statusByOutcome = {
  AWARDED: (result) => ({
    title: `${numberFormatter.format(result.pointsAwarded)} points credited`,
    description: "Your rent payment was processed successfully.",
    tone: "success",
  }),
  DUPLICATE: () => ({
    title: "Duplicate event skipped",
    description:
      "This payment was already processed, so no points were credited again.",
    tone: "neutral",
  }),
  CAPPED: () => ({
    title: "Monthly cap reached",
    description:
      "You have reached this month's points limit, so this payment did not earn additional points.",
    tone: "warning",
  }),
};

const unknownStatus = () => ({
  title: "Payment status unavailable",
  description: "We could not determine how this payment was processed.",
  tone: "neutral",
});

export function buildViewModel(result, member) {
  const progressPercent =
    member.monthlyCap > 0
      ? Math.min(100, (member.pointsThisMonth / member.monthlyCap) * 100)
      : 0;
  const buildStatus = statusByOutcome[result.outcome] ?? unknownStatus;

  return { ...buildStatus(result), progressPercent };
}

export function renderDashboard(result, member) {
  const view = buildViewModel(result, member);

  document.querySelector("[data-status]").className =
    `rounded-2xl border p-5 ${toneClasses[view.tone]}`;
  document.querySelector("[data-status-title]").textContent = view.title;
  document.querySelector("[data-status-description]").textContent =
    view.description;
  document.querySelector("[data-points]").textContent = numberFormatter.format(
    member.pointsThisMonth,
  );
  document.querySelector("[data-streak]").textContent =
    `${member.streakMonths} month streak`;
  document.querySelector("[data-progress]").style.width =
    `${view.progressPercent}%`;
  document.querySelector("[data-progress-label]").textContent =
    `${Math.round(view.progressPercent)}% of monthly cap`;
}
