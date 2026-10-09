"""Stable ids for seeded rows.

The frontend's fake data layer uses readable ids ("u-seoyeon", "s-new"). The database uses uuids; seeded rows
get uuid5(NAMESPACE, readable id) so dev data, the base seed and the browser tests all agree on them.
"""
import uuid

NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "https://openpms.local/seed-ids")


def uid(readable: str) -> str:
    return str(uuid.uuid5(NAMESPACE, readable))


if __name__ == "__main__":
    import sys

    for a in sys.argv[1:]:
        print(a, uid(a))
