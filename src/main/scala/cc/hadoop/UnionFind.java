package cc.hadoop;

import cc.hadoop.utils.LongPairWritable;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Iterator;

public class UnionFind {

    Long2LongOpenHashMap parent;

    public UnionFind(){
        parent = new Long2LongOpenHashMap();
        parent.defaultReturnValue(-1);
    }

    private void union(long a, long b) {
        long r1 = find(a);
        long r2 = find(b);

        if(r1 > r2){
            parent.put(r1, r2);
        }
        else if(r1 < r2){
            parent.put(r2, r1);
        }
    }

    private long find(long x) {

        long p = parent.get(x);
        if(p != -1){
            long new_p = find(p);
            parent.put(x, new_p);
            return new_p;
        }
        else{
            return x;
        }

    }

    public Iterator<LongPairWritable> run(Iterator<LongPairWritable> longPairs){

        while(longPairs.hasNext()){
            LongPairWritable pair = longPairs.next();

            long u = pair.i;
            long v = pair.j;

            if(find(u) != find(v)){
                union(u, v);
            }
        }

        return new Iterator<LongPairWritable>(){

            ObjectIterator<Long2LongMap.Entry> it = parent.long2LongEntrySet().fastIterator();
            LongPairWritable out = new LongPairWritable();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public LongPairWritable next() {
                Long2LongMap.Entry pair = it.next();
                out.i = pair.getLongKey();
                out.j = find(pair.getLongValue());
                return out;
            }
        };

    }


}