/*******************************************************************************
 * Copyright (c) 2003-2007 Kepler Project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/

package party.iroiro.jua;

import java.lang.reflect.*;

/**
 * Class that contains functions accessed by Lua.
 *
 * @author Thomas Slusny
 * @author Thiago Ponte
 */
@SuppressWarnings({"rawtypes", "unused", "SynchronizationOnLocalVariableOrMethodParameter"})
public final class LuaJava {
    LuaJava() {
    }

    /**
     * Java implementation of the metamethod __index for normal objects
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that indicates the state used
     * @param obj        Object to be indexed
     * @param methodName the name of the method
     * @return number of returned objects
     * @throws LuaException when there is a reflection exception
     */
    public static int objectIndex(int stateIndex, Object obj, String methodName) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);

        synchronized (L) {
            int top = L.getTop();
            Object[] objs = new Object[top - 1];

            Method method;
            Class clazz;
            if (obj instanceof Class) {
                clazz = (Class) obj;
                // First try. Static methods of the object
                method = getMethod(L, clazz, methodName, objs, top);
                if (method == null) {
                    // Second try. Methods of the Class class
                    clazz = Class.class;
                    method = getMethod(L, clazz, methodName, objs, top);
                }
            } else {
                clazz = obj.getClass();
                method = getMethod(L, clazz, methodName, objs, top);
            }
            // If method is null means there isn't one receiving the given arguments
            if (method == null) {
                throw new LuaException("Invalid method call. No such method.");
            }
            Object ret;
            try {
                if (Modifier.isPublic(method.getModifiers())) {
                    method.setAccessible(true);
                }

                if (Modifier.isStatic(method.getModifiers())) {
                    ret = method.invoke(null, objs);
                } else {
                    ret = method.invoke(obj, objs);
                }
            } catch (Exception e) {
                throw new LuaException(e);
            }
            // Void function returns null
            if (ret == null) {
                return 0;
            }
            if (ret instanceof LuaReturn) {
                return ((LuaReturn) ret).push(L);
            }
            // push result
            L.push(ret);
            return 1;
        }
    }

    /**
     * Java function that implements the __index for Java arrays
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that indicates the state used
     * @param obj        Object to be indexed
     * @param index      index number of array. Since Lua index starts from 1,
     *                   the number used will be (index - 1)
     * @return number of returned objects
     * @throws LuaException when <code>obj</code> is not an array or index out of bound
     */
    public static int arrayIndex(int stateIndex, Object obj, int index) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            if (!obj.getClass().isArray()) {
                throw new LuaException("Object indexed is not an array.");
            }

            if (Array.getLength(obj) < index) {
                throw new LuaException("Index out of bounds.");
            }

            L.push(Array.get(obj, index - 1));
            return 1;
        }
    }

    /**
     * Java function to be called when a java Class metamethod __index is called.
     * This function returns 1 if there is a field with searchName and 2 if there
     * is a method with the searchName
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param clazz      class to be indexed
     * @param searchName name of the field or method to be accessed
     * @return 1 if there is a field with searchName and 2 for a method
     */
    public static int classIndex(int stateIndex, Class clazz, String searchName) {
        synchronized (LuaFactory.getExisting(stateIndex)) {
            if (checkField(stateIndex, clazz, searchName) != 0) {
                return 1;
            }
            if (checkMethod(stateIndex, clazz, searchName) != 0) {
                return 2;
            }
            return 0;
        }
    }


    /**
     * Java function to be called when a java object metamethod __newindex is called.
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param obj        object to be used
     * @param fieldName  name of the field to be set
     * @return always 0
     * @throws LuaException when there is a reflection exception
     */
    public static int objectNewIndex(int stateIndex, Object obj, String fieldName) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);

        synchronized (L) {
            Field field;
            Class objClass;

            if (obj instanceof Class) {
                objClass = (Class) obj;
            } else {
                objClass = obj.getClass();
            }

            try {
                field = objClass.getField(fieldName);
            } catch (Exception e) {
                throw new LuaException("Error accessing field.", e);
            }

            Class type = field.getType();
            Object setObj = compareTypes(L, type, 3);

            if (field.isAccessible()) {
                field.setAccessible(true);
            }

            try {
                field.set(obj, setObj);
            } catch (IllegalArgumentException e) {
                throw new LuaException("Ilegal argument to set field.", e);
            } catch (IllegalAccessException e) {
                throw new LuaException("Field not accessible.", e);
            }

            return 0;
        }
    }


    /**
     * Java function to be called when a java array metamethod __newindex is called.
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param obj        object to be used
     * @param index      index number of array. Since Lua index starts from 1,
     *                   the number used will be (index - 1)
     * @return always 0
     * @throws LuaException when <code>obj</code> is not an array or index out of bound
     */
    public static int arrayNewIndex(int stateIndex, Object obj, int index) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            if (!obj.getClass().isArray()) {
                throw new LuaException("Object indexed is not an array.");
            }

            if (Array.getLength(obj) < index) {
                throw new LuaException("Index out of bounds.");
            }

            Class type = obj.getClass().getComponentType();
            Object setObj = compareTypes(L, type, 3);
            Array.set(obj, index - 1, setObj);
        }
        return 0;
    }

    /**
     * Pushes a new instance of a java Object of the type clazz
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param clazz      class to be instanciated
     * @return always 1
     * @throws LuaException passed from <code>getObjInstance</code>
     */
    public static int javaNew(int stateIndex, Class clazz) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            Object ret = getObjInstance(L, clazz);
            L.push(ret);
            return 1;
        }
    }

    /**
     * Pushes a new instance of a java Object of the type className
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param luaState  int that represents the state to be used
     * @param className name of the class
     * @return always 1
     * @throws LuaException if class not found or construction failed
     */
    public static int javaNew(int luaState, String className) throws LuaException {
        Lua L = LuaFactory.getExisting(luaState);
        synchronized (L) {
            Class clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new LuaException(e);
            }
            Object ret = getObjInstance(L, clazz);
            L.push(ret);
            return 1;
        }
    }

    /**
     * Calls the static method <code>methodName</code> in class <code>className</code>
     * that receives a Lua as first parameter and return int.
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param className  name of the class that has the open library method
     * @param methodName method to open library
     * @return what the method call returns
     * @throws LuaException if no such method found
     */
    public static int javaLoadLib(int stateIndex, String className, String methodName) throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            Class clazz;

            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new LuaException(e);
            }

            try {
                @SuppressWarnings("unchecked")
                Method mt = clazz.getMethod(methodName, Lua.class);
                if(Integer.class.isAssignableFrom(mt.getReturnType())) {
                    Object obj = mt.invoke(null, L);
                    return (int) obj;
                }
            } catch (Exception e) {
                throw new LuaException("Error on calling method. Library could not be loaded.", e);
            }

            throw new LuaException("Method return type incorrect, expecting int.");
        }
    }

    /**
     * Get a new instance of clazz with the constructor matching
     * parameters on Lua stack
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param L the lua state
     * @param clazz {@link Class} of the new instance
     * @return the new instance
     * @throws LuaException when no such constructor found or construction fails
     */
    private static Object getObjInstance(Lua L, Class clazz) throws LuaException {
        synchronized (L) {
            int top = L.getTop();
            Object[] objs = new Object[top - 1];

            Constructor[] constructors = clazz.getConstructors();
            Constructor constructor = null;

            // gets method and arguments
            for (Constructor value : constructors) {
                Class[] parameters = value.getParameterTypes();
                if (parameters.length != top - 1) {
                    continue;
                }

                boolean okConstruc = true;

                for (int j = 0; j < parameters.length; j++) {
                    try {
                        objs[j] = compareTypes(L, parameters[j], j + 2);
                    } catch (Exception e) {
                        okConstruc = false;
                        break;
                    }
                }

                if (okConstruc) {
                    constructor = value;
                    break;
                }
            }
            // If method is null means there isn't one receiving the given arguments
            if (constructor == null) {
                throw new LuaException("Invalid method call. No such method.");
            }
            try {
                return constructor.newInstance(objs);
            } catch (Exception e) {
                throw new LuaException(e);
            }
        }
    }

    /**
     * Checks if there is a field on the obj with the given name
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param obj        object to be inspected
     * @param fieldName  name of the field to be inpected
     * @return number of returned objects
     */
    public static int checkField(int stateIndex, Object obj, String fieldName) {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            Class objClass;

            if (obj instanceof Class) {
                objClass = (Class) obj;
            } else {
                objClass = obj.getClass();
            }

            Field field;

            try {
                field = objClass.getField(fieldName);
            } catch (Exception e) {
                return 0;
            }

            Object ret;

            try {
                ret = field.get(obj);
                if (ret == null) return 0;
            } catch (Exception e1) {
                return 0;
            }

            L.push(ret);
            return 1;
        }
    }

    /**
     * Checks to see if there is a method with the given name.
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param obj        object to be inspected
     * @param methodName name of the field to be inpected
     * @return number of returned objects
     */
    private static int checkMethod(int stateIndex, Object obj, String methodName) {
        Lua L = LuaFactory.getExisting(stateIndex);
        synchronized (L) {
            Class clazz;

            if (obj instanceof Class) {
                clazz = (Class) obj;
            } else {
                clazz = obj.getClass();
            }

            Method[] methods = clazz.getMethods();

            for (Method method : methods) {
                if (method.getName().equals(methodName)) return 1;
            }

            return 0;
        }
    }

    /**
     * Function that creates an object proxy and pushes it into the stack
     *
     * <code>synchronized</code> on the {@link Lua} state
     *
     * @param stateIndex int that represents the state to be used
     * @param implem     interfaces implemented separated by comma (<code>,</code>)
     * @return number of returned objects
     * @throws LuaException
     */
    public static int createProxyObject(int stateIndex, String implem)
            throws LuaException {
        Lua L = LuaFactory.getExisting(stateIndex);

        synchronized (L) {
            try {
                if (!(L.isTable(2))) {
                    throw new LuaException("Parameter is not a table. Can't create proxy.");
                }

                LuaValue luaObj = L.pull(2);
                Object proxy = luaObj.createProxy(implem);
                L.push(proxy);
            } catch (Exception e) {
                throw new LuaException(e);
            }

            return 1;
        }
    }

    /**
     * Convert element at idx on Lua stack to the given <code>parameter</code>
     * type, if the type matches, null if nil.
     * Throws exception if not match
     *
     * @param L the Lua state
     * @param parameter the {@link Class} of converted element
     * @param idx index
     * @return converted element, null if nil
     * @throws LuaException if the <code>parameter</code> type does not match
     */
    private static Object compareTypes(Lua L, Class<?> parameter, int idx) throws LuaException {
        boolean okType = true;
        Object obj = null;

        if (L.isBoolean(idx)) {
            if (parameter.isPrimitive() && parameter != Boolean.TYPE) {
                okType = false;
            } else if (!parameter.isAssignableFrom(Boolean.class)) {
                okType = false;
            }

            obj = L.toBoolean(idx);
        } else if (L.type(idx) == Lua.STRING) {
            if (!parameter.isAssignableFrom(String.class)) {
                okType = false;
            } else {
                obj = L.toString(idx);
            }
        } else if (L.isFunction(idx)) {
            if (!parameter.isAssignableFrom(LuaValue.class)) {
                okType = false;
            } else {
                obj = L.pull(idx);
            }
        } else if (L.isTable(idx)) {
            if (!parameter.isAssignableFrom(LuaValue.class)) {
                okType = false;
            } else {
                obj = L.pull(idx);
            }
        } else if (L.type(idx) == Lua.NUMBER) {
            double db = L.toNumber(idx).doubleValue();

            obj = LuaUtils.convertNumber(db, parameter);
            if (obj == null) {
                okType = false;
            }
        } else if (L.isUserdata(idx)) {
            if (L.isObject(idx)) {
                Object userObj = L.toObject(idx);
                if (!parameter.isAssignableFrom(userObj.getClass())) {
                    okType = false;
                } else {
                    obj = userObj;
                }
            } else {
                if (!parameter.isAssignableFrom(LuaValue.class)) {
                    okType = false;
                } else {
                    obj = L.pull(idx);
                }
            }
        } else if (L.isNil(idx)) {
            return null;
        } else {
            throw new LuaException("Invalid Parameters.");
        }

        if (!okType) {
            throw new LuaException("Invalid Parameter.");
        }

        return obj;
    }

    /**
     * Get a method matching parameters on Lua stack and convert
     * on-stack parameters into Java objects in <code>retObjs</code>
     *
     * @param L the Lua state
     * @param clazz the Class to search for the method
     * @param methodName the method name
     * @param retObjs where the converted objects store
     * @param top parameter count plus one
     * @return the method found or <code>null</code>
     */
    private static Method getMethod(Lua L, Class clazz, String methodName, Object[] retObjs, int top) {
        Object[] objs = new Object[top - 1];

        Method[] methods = clazz.getMethods();
        Method method = null;

        // gets method and arguments
        for (Method value : methods) {
            if (!value.getName().equals(methodName)) {
                continue;
            }
            Class[] parameters = value.getParameterTypes();
            if (parameters.length != top - 1) {
                continue;
            }
            boolean okMethod = true;

            for (int j = 0; j < parameters.length; j++) {
                try {
                    objs[j] = compareTypes(L, parameters[j], j + 2);
                } catch (Exception e) {
                    okMethod = false;
                    break;
                }
            }

            if (okMethod) {
                method = value;
                System.arraycopy(objs, 0, retObjs, 0, objs.length);
                break;
            }
        }

        return method;
    }
}