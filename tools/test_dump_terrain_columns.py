"""Regression checks for preserving impassable terrain in navigation fixtures."""
import unittest

from dump_terrain_columns import classify, WALL, SOFT


class TerrainClassificationTest(unittest.TestCase):
    def test_unbreakable_blocks_remain_unbreakable(self):
        for name in ('minecraft:bedrock', 'minecraft:barrier'):
            self.assertEqual((WALL, False), classify(name))

    def test_netherrack_and_air_keep_their_distinct_movement_costs(self):
        self.assertEqual((SOFT, False), classify('minecraft:netherrack'))
        self.assertEqual((None, False), classify('minecraft:air'))


if __name__ == '__main__':
    unittest.main()
