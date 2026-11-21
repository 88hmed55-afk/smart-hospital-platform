import { useI18n } from "../i18n";

interface PaginationProps {
  page: number;
  totalPages: number;
  total: number;
  onChange: (page: number) => void;
}

export function Pagination({ page, totalPages, total, onChange }: PaginationProps) {
  const { t } = useI18n();

  if (totalPages <= 1) {
    return null;
  }

  return (
    <div className="pagination">
      <span className="muted">
        {t("common.total")}: {total}
      </span>
      <div className="pagination-controls">
        <button
          type="button"
          className="button button-ghost button-sm"
          disabled={page <= 0}
          onClick={() => onChange(page - 1)}
        >
          {t("common.prev")}
        </button>
        <span className="pagination-page">
          {page + 1} / {totalPages}
        </span>
        <button
          type="button"
          className="button button-ghost button-sm"
          disabled={page >= totalPages - 1}
          onClick={() => onChange(page + 1)}
        >
          {t("common.next")}
        </button>
      </div>
    </div>
  );
}
